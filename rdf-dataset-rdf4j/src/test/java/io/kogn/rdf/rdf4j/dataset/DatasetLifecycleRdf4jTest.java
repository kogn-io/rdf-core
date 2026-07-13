// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.dataset.DatasetStoreConfig.Persistence;
import io.kogn.rdf.rdf4j.RDF4JFactory;
import io.kogn.rdf.rdf4j.RDF4JIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;

/**
 * Tests for {@link DatasetLifecycleRdf4j} — the lease-based in-flight protection,
 * the one-time on-create hook, path-traversal safety and the basic lifecycle
 * contract.
 */
class DatasetLifecycleRdf4jTest {

  private static final IRI GRAPH = RDF4JIRI.of("https://example.org/graph/1");
  private static final IRI SUBJECT = RDF4JIRI.of("https://example.org/subject");
  private static final IRI PREDICATE = RDF4JIRI.of("https://example.org/predicate");
  private static final IRI OBJECT = RDF4JIRI.of("https://example.org/object");

  private final RDF4JFactory rdf = new RDF4JFactory();
  private DatasetLifecycleRdf4j lifecycle;

  @AfterEach
  void tearDown() {
    if (lifecycle != null) {
      lifecycle.shutDownAll();
    }
  }

  private DatasetLifecycleRdf4j inMemory() {
    lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.IN_MEMORY, false), null);
    return lifecycle;
  }

  private Graph singleTriple() {
    final Graph graph = rdf.createGraph();
    graph.add(rdf.createTriple(SUBJECT, PREDICATE, OBJECT));
    return graph;
  }

  private static final String ASK_GRAPH = "ASK { GRAPH <" + GRAPH.getIRIString() + "> { ?s ?p ?o } }";

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("contract")
  class Contract {

    @Test
    @DisplayName("acquire opens a usable dataset; writes are visible through the same handle")
    void acquire_writeThenRead_visible() {
      try (DatasetHandle ds = inMemory().acquire(new DatasetId("a"))) {
        ds.graphStore().add(GRAPH, singleTriple());

        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }
    }

    @Test
    @DisplayName("two handles for the same id share the same underlying store")
    void acquire_sameId_sharesState() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final DatasetId id = new DatasetId("shared");
      try (DatasetHandle writer = lc.acquire(id); DatasetHandle reader = lc.acquire(id)) {
        writer.graphStore().add(GRAPH, singleTriple());

        assertThat(reader.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }
    }

    @Test
    @DisplayName("list reflects acquired datasets")
    void list_afterAcquire_containsId() {
      final DatasetLifecycleRdf4j lc = inMemory();
      try (DatasetHandle ds = lc.acquire(new DatasetId("listed"))) {
        assertThat(lc.list()).contains(new DatasetId("listed"));
      }
    }

    @Test
    @DisplayName("close is a no-op while a lease is open")
    void close_whileLeaseOpen_isNoOp() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final DatasetId id = new DatasetId("busy");
      try (DatasetHandle ds = lc.acquire(id)) {
        ds.graphStore().add(GRAPH, singleTriple());

        lc.close(id); // must not tear the store down under the open lease

        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }
    }
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("in-flight protection")
  class InFlightProtection {

    @Test
    @DisplayName("delete throws while a lease is open and the open handle stays usable")
    void delete_whileLeaseOpen_throwsAndHandleSurvives() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final DatasetId id = new DatasetId("protected");
      try (DatasetHandle ds = lc.acquire(id)) {
        assertThatThrownBy(() -> lc.delete(id)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("open leases");

        ds.graphStore().add(GRAPH, singleTriple());
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }

      lc.delete(id); // succeeds once the lease is released
      assertThat(lc.list()).doesNotContain(id);
    }

    @Test
    @DisplayName("concurrent acquire/operate/close vs. eviction never hits a shut-down store")
    void concurrentAcquireVsEviction_noOperationFailure() throws Exception {
      final DatasetLifecycleRdf4j lc = inMemory();
      final DatasetId id = new DatasetId("race");
      final int workers = 6;
      final int iterations = 300;
      final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
      final ExecutorService pool = Executors.newFixedThreadPool(workers + 1);
      try {
        // Evictor: hammers the eviction trigger; must be a no-op while leases are open.
        final Future<?> evictor = pool.submit(() -> {
          for (int i = 0; i < iterations * workers; i++) {
            try {
              lc.close(id);
            } catch (final RuntimeException e) {
              failures.add(e);
            }
          }
        });
        // Workers: acquire → operate → close. The operation must never see a closed store.
        for (int w = 0; w < workers; w++) {
          pool.submit(() -> {
            for (int i = 0; i < iterations; i++) {
              try (DatasetHandle ds = lc.acquire(id)) {
                ds.sparqlQuery().ask(ASK_GRAPH);
              } catch (final Throwable t) {
                failures.add(t);
              }
            }
          });
        }
        evictor.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
      } finally {
        pool.shutdownNow();
      }

      assertThat(failures).isEmpty();
    }
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("on-create hook")
  class OnCreateHook {

    @Test
    @DisplayName("hook fires once on creation, not on re-acquire, and its seed is visible immediately")
    void onCreate_firesOnceAndSeedVisible() {
      final AtomicInteger calls = new AtomicInteger();
      lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.IN_MEMORY, false), null,
          DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, (id, graphStore) -> {
            calls.incrementAndGet();
            graphStore.add(GRAPH, singleTriple());
          });
      final DatasetId id = new DatasetId("seeded");

      try (DatasetHandle ds = lifecycle.acquire(id)) {
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue(); // seed already present on first handout
      }
      try (DatasetHandle ds = lifecycle.acquire(id)) {
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }

      assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("concurrent first-acquire seeds exactly once and both callers see the seed")
    void onCreate_concurrentFirstAcquire_seedsOnce() throws Exception {
      final AtomicInteger calls = new AtomicInteger();
      lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.IN_MEMORY, false), null,
          DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, (id, graphStore) -> {
            calls.incrementAndGet();
            graphStore.add(GRAPH, singleTriple());
          });
      final DatasetId id = new DatasetId("concurrent-seed");
      final int threads = 8;
      final ExecutorService pool = Executors.newFixedThreadPool(threads);
      final ConcurrentLinkedQueue<Boolean> seen = new ConcurrentLinkedQueue<>();
      try {
        final List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
          futures.add(pool.submit(() -> {
            try (DatasetHandle ds = lifecycle.acquire(id)) {
              seen.add(ds.sparqlQuery().ask(ASK_GRAPH));
            }
          }));
        }
        for (final Future<?> f : futures) {
          f.get(30, TimeUnit.SECONDS);
        }
      } finally {
        pool.shutdownNow();
      }

      assertThat(calls).hasValue(1);
      assertThat(seen).hasSize(threads).containsOnly(true);
    }

    @Test
    @DisplayName("a throwing on-create rolls back: store not leaked, persistent dir removed, retry seeds cleanly")
    void onCreate_throwsFirstTime_rollsBackAndRetrySucceeds(@TempDir final Path tmp) {
      final AtomicInteger calls = new AtomicInteger();
      final Path root = tmp.resolve("stores");
      lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false), root,
          DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, (id, graphStore) -> {
            if (calls.incrementAndGet() == 1) {
              throw new IllegalStateException("boom");
            }
            graphStore.add(GRAPH, singleTriple());
          });
      final DatasetId id = new DatasetId("rollback");

      assertThatThrownBy(() -> lifecycle.acquire(id)).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("boom");

      // rolled back: nothing left behind, and the retry re-runs onCreate over a fresh store
      // (only possible if the failed store's lock was released and its dir removed).
      assertThat(lifecycle.list()).doesNotContain(id);
      try (DatasetHandle ds = lifecycle.acquire(id)) {
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }
      assertThat(calls).hasValue(2);
    }
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("persistence and path safety")
  class Persistence_ {

    @TempDir
    Path tmp;

    private DatasetLifecycleRdf4j persistent(final Path storageRoot) {
      lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false), storageRoot);
      return lifecycle;
    }

    @Test
    @DisplayName("list scans on-disk datasets even after eviction")
    void list_survivesEviction_forPersistentStore() {
      final Path root = tmp.resolve("stores");
      final DatasetLifecycleRdf4j lc = persistent(root);
      final DatasetId id = new DatasetId("on-disk");
      lc.acquire(id).close();

      lc.close(id); // evict from cache; storage stays

      assertThat(lc.list()).contains(id);
    }

    @Test
    @DisplayName("delete removes the on-disk storage")
    void delete_removesStorage() throws Exception {
      final Path root = tmp.resolve("stores");
      final DatasetLifecycleRdf4j lc = persistent(root);
      final DatasetId id = new DatasetId("to-delete");
      lc.acquire(id).close();
      assertThat(Files.list(root).count()).isEqualTo(1L);

      lc.delete(id);

      assertThat(lc.list()).doesNotContain(id);
      assertThat(Files.exists(root) ? Files.list(root).count() : 0L).isEqualTo(0L);
    }

    @Test
    @DisplayName("an id containing '../' cannot escape the storage root")
    void acquire_pathTraversalId_staysWithinRoot() throws Exception {
      final Path root = tmp.resolve("stores");
      final DatasetLifecycleRdf4j lc = persistent(root);

      try (DatasetHandle ds = lc.acquire(new DatasetId("../escape"))) {
        ds.graphStore().add(GRAPH, singleTriple()); // dataset is fully usable
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }

      // nothing leaked next to the storage root …
      try (Stream<Path> siblings = Files.list(tmp)) {
        assertThat(siblings).containsExactly(root);
      }
      // … and exactly one (encoded) child directory exists inside it
      try (Stream<Path> children = Files.list(root)) {
        assertThat(children).hasSize(1).allMatch(p -> p.startsWith(root));
      }
    }
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("configuration")
  class Configuration {

    @Test
    @DisplayName("full-text search is rejected until #19")
    void fullTextSearch_rejected() {
      assertThatThrownBy(() -> new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.IN_MEMORY, true), null))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("#19");
    }
  }
}
