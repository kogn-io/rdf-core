// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.DatasetExport;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.RdfFormat;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.dataset.hosting.DatasetCleanupOutcome;
import io.kogn.rdf.dataset.hosting.DatasetCloseOutcome;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig.Persistence;
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
  private static final String INSERT_TRIPLE = "INSERT DATA { GRAPH <" + GRAPH.getIRIString() + "> { <"
      + SUBJECT.getIRIString() + "> <" + PREDICATE.getIRIString() + "> <" + OBJECT.getIRIString() + "> } }";

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
    @DisplayName("close reports STILL_LEASED and is a no-op while a lease is open")
    void close_whileLeaseOpen_isNoOp() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final DatasetId id = new DatasetId("busy");
      try (DatasetHandle ds = lc.acquire(id)) {
        ds.graphStore().add(GRAPH, singleTriple());

        // must not tear the store down under the open lease
        assertThat(lc.close(id)).isEqualTo(DatasetCloseOutcome.STILL_LEASED);

        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }
    }

    @Test
    @DisplayName("close reports CLOSED once the lease holding it open is released")
    void close_afterLeaseReleased_reportsClosed() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final DatasetId id = new DatasetId("idle");
      lc.acquire(id).close();

      assertThat(lc.close(id)).isEqualTo(DatasetCloseOutcome.CLOSED);
    }

    @Test
    @DisplayName("close reports NOT_OPEN for an id the lifecycle has never seen")
    void close_unknownId_reportsNotOpen() {
      final DatasetLifecycleRdf4j lc = inMemory();

      assertThat(lc.close(new DatasetId("never-opened"))).isEqualTo(DatasetCloseOutcome.NOT_OPEN);
    }

    @Test
    @DisplayName("datasetExport() serializes the hosted dataset, graph names included")
    void datasetExport_wholeDataset_containsTheData() {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (DatasetHandle ds = inMemory().acquire(new DatasetId("exported"))) {
        ds.graphStore().add(GRAPH, singleTriple());

        ds.datasetExport().export(out, RdfFormat.TRIG);
      }

      // Asserting that the IRIs reached the stream is all this test is after: it proves the
      // handle really wires the export port to its own store. Serialization semantics per
      // format are the content adapter's business and are covered by DatasetRdf4jTest.
      assertThat(out.toString(StandardCharsets.UTF_8)).contains(GRAPH.getIRIString()).contains(SUBJECT.getIRIString());
    }

    @Test
    @DisplayName("datasetExport() serializes a single named graph")
    void datasetExport_namedGraph_containsThatGraphOnly() {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final IRI otherGraph = RDF4JIRI.of("https://example.org/graph/2");
      try (DatasetHandle ds = inMemory().acquire(new DatasetId("exported-graph"))) {
        ds.graphStore().add(GRAPH, singleTriple());
        ds.graphStore().add(otherGraph, singleTriple());

        ds.datasetExport().export(out, RdfFormat.NQUADS, GRAPH);
      }

      assertThat(out.toString(StandardCharsets.UTF_8)).contains(GRAPH.getIRIString())
          .doesNotContain(otherGraph.getIRIString());
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
  @DisplayName("closed handle")
  class ClosedHandle {

    @Test
    @DisplayName("graphStore() accessor retained after close throws on use")
    void graphStore_afterClose_throws() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final GraphStore retained;
      try (DatasetHandle ds = lc.acquire(new DatasetId("closed-graph-store"))) {
        retained = ds.graphStore();
        retained.add(GRAPH, singleTriple()); // usable while the handle is open
      }

      assertThatThrownBy(() -> retained.add(GRAPH, singleTriple())).isInstanceOf(IllegalStateException.class)
          .hasMessage("handle is closed");
    }

    @Test
    @DisplayName("sparqlQuery() accessor retained after close throws on use")
    void sparqlQuery_afterClose_throws() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final SparqlQuery retained;
      try (DatasetHandle ds = lc.acquire(new DatasetId("closed-sparql-query"))) {
        retained = ds.sparqlQuery();
        assertThat(retained.ask(ASK_GRAPH)).isFalse(); // usable while the handle is open
      }

      assertThatThrownBy(() -> retained.ask(ASK_GRAPH)).isInstanceOf(IllegalStateException.class)
          .hasMessage("handle is closed");
    }

    @Test
    @DisplayName("sparqlUpdate() accessor retained after close throws on use")
    void sparqlUpdate_afterClose_throws() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final SparqlUpdate retained;
      try (DatasetHandle ds = lc.acquire(new DatasetId("closed-sparql-update"))) {
        retained = ds.sparqlUpdate();
        retained.update(INSERT_TRIPLE); // usable while the handle is open
      }

      assertThatThrownBy(() -> retained.update(INSERT_TRIPLE)).isInstanceOf(IllegalStateException.class)
          .hasMessage("handle is closed");
    }

    @Test
    @DisplayName("datasetExport() accessor retained after close throws on use")
    void datasetExport_afterClose_throws() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final DatasetExport retained;
      try (DatasetHandle ds = lc.acquire(new DatasetId("closed-dataset-export"))) {
        retained = ds.datasetExport();
        retained.export(new ByteArrayOutputStream(), RdfFormat.TRIG); // usable while the handle is open
      }

      assertThatThrownBy(() -> retained.export(new ByteArrayOutputStream(), RdfFormat.TRIG))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("handle is closed");
    }

    @Test
    @DisplayName("transactor() accessor retained after close throws on use")
    void transactor_afterClose_throws() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final DatasetTransactor retained;
      try (DatasetHandle ds = lc.acquire(new DatasetId("closed-transactor"))) {
        retained = ds.transactor();
        final boolean result = retained.inTransaction(tx -> tx.ask(ASK_GRAPH));
        assertThat(result).isFalse(); // usable while open
      }

      assertThatThrownBy(() -> retained.inTransaction(tx -> tx.ask(ASK_GRAPH)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("handle is closed");
    }

    @Test
    @DisplayName("closing one handle does not affect a co-existing handle for the same dataset")
    void closingOneHandle_leavesOtherHandleUsable() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final DatasetId id = new DatasetId("closed-one-of-two");
      final DatasetHandle first = lc.acquire(id);
      try (DatasetHandle second = lc.acquire(id)) {
        first.close();

        assertThatThrownBy(() -> first.graphStore().add(GRAPH, singleTriple()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("handle is closed");

        second.graphStore().add(GRAPH, singleTriple());
        assertThat(second.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }
    }

    @Test
    @DisplayName("shutDownAll proceeds despite an open lease (last-resort teardown) and does not throw")
    void shutDownAll_withOpenLease_proceedsWithoutThrowing() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final DatasetId id = new DatasetId("open-during-shutdown");
      final DatasetHandle ds = lc.acquire(id); // intentionally left open

      assertThatCode(lc::shutDownAll).doesNotThrowAnyException();

      assertThat(lc.list()).doesNotContain(id);
      ds.close(); // releasing the lease afterwards must not throw either
    }

    @Test
    @DisplayName("shutDownAll is callable through the DatasetLifecycle port type and is a no-op when repeated")
    void shutDownAll_throughPortType_isRepeatable() {
      final DatasetLifecycle port = inMemory();
      final DatasetId id = new DatasetId("via-port");
      port.acquire(id).close();

      assertThatCode(() -> {
        port.shutDownAll();
        port.shutDownAll();
      }).doesNotThrowAnyException();

      assertThat(port.list()).doesNotContain(id);
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

    @Test
    @DisplayName("an Error from on-create rolls back too: no half dataset, Error propagates unchanged, retry seeds")
    void onCreate_throwsError_rollsBackAndPropagatesUnchanged(@TempDir final Path tmp) {
      final AtomicInteger calls = new AtomicInteger();
      final AssertionError failure = new AssertionError("boom");
      lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false),
          tmp.resolve("stores"), DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, (id, graphStore) -> {
            if (calls.incrementAndGet() == 1) {
              throw failure;
            }
            graphStore.add(GRAPH, singleTriple());
          });
      final DatasetId id = new DatasetId("rollback-error");

      assertThatThrownBy(() -> lifecycle.acquire(id)).isSameAs(failure);

      // same observable state as after a RuntimeException: not listed, lock released, storage
      // removed so the retry is a genuine creation that runs onCreate again.
      assertThat(lifecycle.list()).doesNotContain(id);
      try (DatasetHandle ds = lifecycle.acquire(id)) {
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }
      assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("a hook that calls back into the lifecycle fails fast instead of hanging, and rolls back")
    void onCreate_reentry_failsFastAndRollsBack() {
      final DatasetId other = new DatasetId("other");
      final List<Consumer<DatasetLifecycleRdf4j>> callbacks = List.of(lc -> lc.acquire(other),
          lc -> lc.acquire(new DatasetId("reenter")), lc -> lc.close(other), lc -> lc.delete(other),
          DatasetLifecycleRdf4j::list, DatasetLifecycleRdf4j::listUnfinishedDeletes,
          lc -> lc.clearUnfinishedDelete(other), DatasetLifecycleRdf4j::shutDownAll);
      for (final Consumer<DatasetLifecycleRdf4j> callback : callbacks) {
        final AtomicBoolean reenter = new AtomicBoolean(true);
        final AtomicInteger calls = new AtomicInteger();
        lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.IN_MEMORY, false), null,
            DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, (id, graphStore) -> {
              calls.incrementAndGet();
              if (reenter.get()) {
                callback.accept(lifecycle);
              }
              graphStore.add(GRAPH, singleTriple());
            });
        final DatasetId id = new DatasetId("reenter");

        assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> assertThatThrownBy(() -> lifecycle.acquire(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("onCreate"));

        // rolled back through the ordinary failure path: nothing registered, and the guard
        // is gone once the hook returned, so a well-behaved retry seeds cleanly
        assertThat(lifecycle.list()).isEmpty();
        reenter.set(false);
        try (DatasetHandle ds = lifecycle.acquire(id)) {
          assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
        }
        assertThat(calls).hasValue(2);
        lifecycle.shutDownAll();
      }
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

      assertThat(lc.close(id)).isEqualTo(DatasetCloseOutcome.CLOSED); // evict from cache; storage stays

      assertThat(lc.list()).contains(id);
    }

    @Test
    @DisplayName("closing a persisted-but-not-open dataset again reports NOT_OPEN, not STILL_LEASED")
    void close_twice_reportsClosedThenNotOpen() {
      final Path root = tmp.resolve("stores");
      final DatasetLifecycleRdf4j lc = persistent(root);
      final DatasetId id = new DatasetId("closed-twice");
      lc.acquire(id).close(); // release the lease

      assertThat(lc.close(id)).isEqualTo(DatasetCloseOutcome.CLOSED);
      assertThat(lc.close(id)).isEqualTo(DatasetCloseOutcome.NOT_OPEN);

      // the storage is still there — an idle/TTL policy iterating list() must find it
      assertThat(lc.list()).contains(id);
    }

    @Test
    @DisplayName("close discards data for an in-memory store — there is no storage to resume")
    void close_discardsData_forInMemoryStore() {
      final AtomicInteger calls = new AtomicInteger();
      lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.IN_MEMORY, false), null,
          DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, (id, graphStore) -> calls.incrementAndGet());
      final DatasetId id = new DatasetId("in-memory-evicted");
      try (DatasetHandle ds = lifecycle.acquire(id)) {
        ds.graphStore().add(GRAPH, singleTriple());
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue(); // precondition: the data is really there
      }

      // evict — but for IN_MEMORY there is no persisted state to resume
      assertThat(lifecycle.close(id)).isEqualTo(DatasetCloseOutcome.CLOSED);

      try (DatasetHandle ds = lifecycle.acquire(id)) {
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isFalse(); // data is gone
      }
      assertThat(calls).hasValue(2); // onCreate fired again, as if this were a brand-new dataset
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
    @DisplayName("a delete whose on-disk teardown keeps failing refuses the next acquire instead of serving the remains")
    void delete_diskTeardownKeepsFailing_nextAcquireRefusesTheRemains() {
      final Path root = tmp.resolve("stores");
      final DatasetId id = new DatasetId("undeletable");
      final UncheckedIOException diskFailure = new UncheckedIOException(new IOException("simulated disk failure"));
      final DatasetLifecycleRdf4j lc = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false),
          root, DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, null) {
        @Override
        void deleteStorageOnDisk(final DatasetId toDelete) {
          if (toDelete.equals(id)) {
            throw diskFailure; // the OS refuses to remove the storage — this time and every time
          }
          super.deleteStorageOnDisk(toDelete);
        }
      };
      lifecycle = lc;
      try (DatasetHandle ds = lc.acquire(id)) {
        ds.graphStore().add(GRAPH, singleTriple()); // handle released, but the entry stays cached
      }

      assertThatThrownBy(() -> lc.delete(id)).isSameAs(diskFailure);

      // the cleanup cannot be finished — so the dataset is refused rather than opened over
      // whatever remains, and it stays refused for as long as they are there.
      assertThatThrownBy(() -> lc.acquire(id)).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("unfinished delete")
          .hasCauseReference(diskFailure);
      assertThatThrownBy(() -> lc.acquire(id)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("list omits the remains of a failed delete while still reporting an intact dataset alongside them")
    void list_omitsRemainsOfFailedDelete_butKeepsTheIntactDataset() throws Exception {
      final Path root = tmp.resolve("stores");
      final DatasetId broken = new DatasetId("half-deleted");
      final DatasetId intact = new DatasetId("still-there");
      final UncheckedIOException diskFailure = new UncheckedIOException(new IOException("simulated disk failure"));
      final DatasetLifecycleRdf4j lc = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false),
          root, DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, null) {
        @Override
        void deleteStorageOnDisk(final DatasetId toDelete) {
          if (toDelete.equals(broken)) {
            throw diskFailure; // the OS refuses to remove the storage — this time and every time
          }
          super.deleteStorageOnDisk(toDelete);
        }
      };
      lifecycle = lc;
      lc.acquire(broken).close();
      lc.acquire(intact).close();
      assertThat(lc.list()).contains(broken, intact); // precondition: both are reported while both are usable

      assertThatThrownBy(() -> lc.delete(broken)).isSameAs(diskFailure);

      // the remains are still on disk — but they are no longer a dataset the port will open, so a
      // consumer iterating the listing must not be handed the identifier at all.
      assertThat(Files.list(root).count()).isEqualTo(2L);
      assertThat(lc.list()).containsExactly(intact);
      assertThatThrownBy(() -> lc.acquire(broken)).isInstanceOf(IllegalStateException.class);
      assertThatCode(() -> lc.acquire(intact).close()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a delete that failed part-way through is cleaned up by the next acquire, which seeds a fresh dataset")
    void delete_diskTeardownFailsPartWay_nextAcquireCleansUpAndSeedsAfresh() throws Exception {
      final Path root = tmp.resolve("stores");
      final DatasetId id = new DatasetId("half-deleted");
      final IRI otherGraph = RDF4JIRI.of("https://example.org/graph/2");
      final String askOther = "ASK { GRAPH <" + otherGraph.getIRIString() + "> { ?s ?p ?o } }";
      final UncheckedIOException diskFailure = new UncheckedIOException(new IOException("simulated disk failure"));
      final AtomicBoolean failNext = new AtomicBoolean(true);
      final AtomicInteger seeds = new AtomicInteger();
      final DatasetLifecycleRdf4j lc = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false),
          root, DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, (seeded, graphStore) -> {
            seeds.incrementAndGet();
            graphStore.add(GRAPH, singleTriple());
          }) {
        @Override
        void deleteStorageOnDisk(final DatasetId toDelete) {
          if (toDelete.equals(id) && failNext.compareAndSet(true, false)) {
            deleteOneFileOfTheSoleDataset(root); // gets part-way through, then gives up
            throw diskFailure;
          }
          super.deleteStorageOnDisk(toDelete); // the cleanup retried by acquire succeeds
        }
      };
      lifecycle = lc;
      final GraphStore firstStore;
      try (DatasetHandle ds = lc.acquire(id)) {
        ds.graphStore().add(otherGraph, singleTriple()); // written after seeding — tells old from new
        firstStore = ds.graphStore();
      }

      assertThatThrownBy(() -> lc.delete(id)).isSameAs(diskFailure);

      // precondition of the hazard: the remains are still on disk, so the directory is not empty
      // and would be read as "existing dataset" by the next create.
      assertThat(soleDatasetDirectory(root)).isNotEmptyDirectory();
      // hidden from the listing, though the acquire below reopens it after cleanup
      assertThat(lc.list()).doesNotContain(id);

      try (DatasetHandle ds = lc.acquire(id)) {
        assertThat(seeds).hasValue(2); // the remains were cleared away, so this is a genuine creation
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue(); // freshly seeded
        assertThat(ds.sparqlQuery().ask(askOther)).isFalse(); // and nothing of the old dataset survived
        assertThat(ds.graphStore()).isNotSameAs(firstStore); // the dead cache entry did not survive either
      }
    }

    @Test
    @DisplayName("a repeated delete that succeeds clears the unfinished-delete mark")
    void delete_retriedSuccessfully_clearsTheMark() {
      final Path root = tmp.resolve("stores");
      final DatasetId id = new DatasetId("retry-delete");
      final UncheckedIOException diskFailure = new UncheckedIOException(new IOException("simulated disk failure"));
      final AtomicBoolean failNext = new AtomicBoolean(true);
      final DatasetLifecycleRdf4j lc = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false),
          root, DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, null) {
        @Override
        void deleteStorageOnDisk(final DatasetId toDelete) {
          if (toDelete.equals(id) && failNext.compareAndSet(true, false)) {
            throw diskFailure;
          }
          super.deleteStorageOnDisk(toDelete);
        }
      };
      lifecycle = lc;
      lc.acquire(id).close();

      assertThatThrownBy(() -> lc.delete(id)).isSameAs(diskFailure);
      lc.delete(id); // the caller retries the delete itself, and this time it goes through

      assertThat(lc.list()).doesNotContain(id);
      assertThatCode(() -> lc.acquire(id).close()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the unfinished-delete mark survives a restart: a second instance over the same storage root"
        + " still refuses the remains if the cleanup keeps failing")
    void delete_diskTeardownFails_secondInstanceOverSameRoot_stillRefusesTheRemains() {
      final Path root = tmp.resolve("stores");
      final DatasetId id = new DatasetId("restart-refused");
      final UncheckedIOException diskFailure = new UncheckedIOException(new IOException("simulated disk failure"));
      final DatasetLifecycleRdf4j first = new DatasetLifecycleRdf4j(
          new DatasetStoreConfig(Persistence.PERSISTENT, false), root, DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, null) {
        @Override
        void deleteStorageOnDisk(final DatasetId toDelete) {
          if (toDelete.equals(id)) {
            throw diskFailure; // the OS refuses to remove the storage
          }
          super.deleteStorageOnDisk(toDelete);
        }
      };
      first.acquire(id).close();
      assertThatThrownBy(() -> first.delete(id)).isSameAs(diskFailure);
      first.shutDownAll(); // the process goes down with the mark still unresolved

      // a brand-new instance — its in-process tracking starts empty, so only the on-disk marker
      // can be what makes it refuse the remains too.
      final DatasetLifecycleRdf4j second = new DatasetLifecycleRdf4j(
          new DatasetStoreConfig(Persistence.PERSISTENT, false), root, DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, null) {
        @Override
        void deleteStorageOnDisk(final DatasetId toDelete) {
          if (toDelete.equals(id)) {
            throw diskFailure; // still refuses to remove the storage after the "restart"
          }
          super.deleteStorageOnDisk(toDelete);
        }
      };
      lifecycle = second;

      assertThat(second.list()).doesNotContain(id); // the marker keeps it out of the listing too
      assertThatThrownBy(() -> second.acquire(id)).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("unfinished delete")
          .hasCauseReference(diskFailure);
    }

    @Test
    @DisplayName("the unfinished-delete mark survives a restart: a second instance over the same storage root"
        + " cleans up the remains and seeds a fresh dataset once the cleanup succeeds")
    void delete_diskTeardownFails_secondInstanceOverSameRoot_cleansUpAndSeedsAfresh() throws Exception {
      final Path root = tmp.resolve("stores");
      final DatasetId id = new DatasetId("restart-recovers");
      final IRI otherGraph = RDF4JIRI.of("https://example.org/graph/2");
      final String askOther = "ASK { GRAPH <" + otherGraph.getIRIString() + "> { ?s ?p ?o } }";
      final UncheckedIOException diskFailure = new UncheckedIOException(new IOException("simulated disk failure"));
      final AtomicInteger seeds = new AtomicInteger();
      final DatasetLifecycleRdf4j first = new DatasetLifecycleRdf4j(
          new DatasetStoreConfig(Persistence.PERSISTENT, false), root, DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC,
          (seeded, graphStore) -> {
            seeds.incrementAndGet();
            graphStore.add(GRAPH, singleTriple());
          }) {
        @Override
        void deleteStorageOnDisk(final DatasetId toDelete) {
          if (toDelete.equals(id)) {
            throw diskFailure; // the OS refuses to remove the storage, and never gets to try again
          }
          super.deleteStorageOnDisk(toDelete);
        }
      };
      try (DatasetHandle ds = first.acquire(id)) {
        ds.graphStore().add(otherGraph, singleTriple()); // written after seeding — tells old from new
      }
      assertThatThrownBy(() -> first.delete(id)).isSameAs(diskFailure);
      first.shutDownAll(); // the process goes down with the mark still unresolved

      // precondition of the hazard: the remains are still on disk, not just the marker.
      assertThat(soleDatasetDirectory(root)).isNotEmptyDirectory();

      // a brand-new instance, real (non-overridden) on-disk teardown this time: the transient
      // failure has cleared, so its retry of the cleanup — triggered by the marker it finds on
      // disk, not by any in-process state, since this instance never attempted the delete itself
      // — succeeds.
      final DatasetLifecycleRdf4j second = new DatasetLifecycleRdf4j(
          new DatasetStoreConfig(Persistence.PERSISTENT, false), root, DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC,
          (seeded, graphStore) -> {
            seeds.incrementAndGet();
            graphStore.add(GRAPH, singleTriple());
          });
      lifecycle = second;

      // hidden from the listing, though the acquire below reopens it after cleanup
      assertThat(second.list()).doesNotContain(id);
      try (DatasetHandle ds = second.acquire(id)) {
        assertThat(seeds).hasValue(2); // the remains were cleared away, so this is a genuine creation
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue(); // freshly seeded
        assertThat(ds.sparqlQuery().ask(askOther)).isFalse(); // and nothing of the old dataset survived
      }
    }

    private Path soleDatasetDirectory(final Path root) throws IOException {
      try (Stream<Path> children = Files.list(root)) {
        return children.filter(Files::isDirectory).findFirst().orElseThrow();
      }
    }

    /** Simulates a storage deletion that removed something before failing. */
    private void deleteOneFileOfTheSoleDataset(final Path root) {
      try (Stream<Path> files = Files.walk(soleDatasetDirectory(root))) {
        Files.delete(files.filter(Files::isRegularFile).findFirst().orElseThrow());
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Test
    @DisplayName("a close whose repository teardown fails still drops the cache entry; retry re-opens a live store")
    void close_teardownFails_dropsCacheEntryAndSurfacesFailure() {
      final Path root = tmp.resolve("stores");
      final DatasetId id = new DatasetId("close-fails");
      final RepositoryException teardownFailure = new RepositoryException("simulated shutdown failure");
      final AtomicBoolean failNext = new AtomicBoolean(true);
      final DatasetLifecycleRdf4j lc = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false),
          root, DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, null) {
        @Override
        void shutDownRepository(final Repository repository) {
          super.shutDownRepository(repository); // real teardown succeeds — lock released, data flushed
          if (failNext.compareAndSet(true, false)) {
            throw teardownFailure; // ...but shutDown() itself is reported as having failed, once
          }
        }
      };
      lifecycle = lc;
      final GraphStore firstStore;
      try (DatasetHandle ds = lc.acquire(id)) {
        ds.graphStore().add(GRAPH, singleTriple());
        firstStore = ds.graphStore();
      }

      assertThatThrownBy(() -> lc.close(id)).isSameAs(teardownFailure);

      // the cache must not keep serving the (already shut-down but now dangling) store: a fresh
      // acquire creates a brand new one — same identity would mean the dead cached entry survived
      // — and the fresh store is still fully usable, with the original data intact on disk.
      try (DatasetHandle ds = lc.acquire(id)) {
        assertThat(ds.graphStore()).isNotSameAs(firstStore);
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }
    }

    @Test
    @DisplayName("shutDownAll shuts every store down and clears the cache even when some teardowns fail")
    void shutDownAll_teardownsFail_attemptsEveryStoreAndSurfacesAllFailures() {
      final Path root = tmp.resolve("stores");
      final List<Repository> tornDown = new ArrayList<>();
      final List<RepositoryException> failures = new ArrayList<>();
      final DatasetLifecycleRdf4j lc = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false),
          root, DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, null) {
        @Override
        void shutDownRepository(final Repository repository) {
          super.shutDownRepository(repository);
          tornDown.add(repository);
          if (failures.size() < 2) { // the first two stores in iteration order report a failure
            final RepositoryException failure = new RepositoryException("simulated shutdown failure");
            failures.add(failure);
            throw failure;
          }
        }
      };
      lifecycle = lc;
      for (final String name : List.of("one", "two", "three")) {
        lc.acquire(new DatasetId(name)).close();
      }

      assertThatThrownBy(lc::shutDownAll).isSameAs(failures.get(0))
          .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(failures.get(1)));

      assertThat(tornDown).hasSize(3).noneMatch(Repository::isInitialized);
      lc.shutDownAll(); // the cache was cleared: nothing is left to tear down a second time
      assertThat(tornDown).hasSize(3);
    }

    @Test
    @DisplayName("an on-create rollback whose teardown fails still removes the store and reports the on-create failure")
    void onCreate_throws_rollbackTeardownFails_reportsOriginalAndStillRollsBack() {
      final Path root = tmp.resolve("stores");
      final DatasetId id = new DatasetId("rollback-teardown-fails");
      final IllegalStateException seedFailure = new IllegalStateException("boom");
      final RepositoryException teardownFailure = new RepositoryException("simulated shutdown failure");
      final AtomicBoolean failNext = new AtomicBoolean(true);
      final AtomicInteger seeds = new AtomicInteger();
      final DatasetLifecycleRdf4j lc = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false),
          root, DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, (datasetId, graphStore) -> {
            if (seeds.incrementAndGet() == 1) {
              throw seedFailure;
            }
            graphStore.add(GRAPH, singleTriple());
          }) {
        @Override
        void shutDownRepository(final Repository repository) {
          super.shutDownRepository(repository); // real teardown succeeds — lock released
          if (failNext.compareAndSet(true, false)) {
            throw teardownFailure;
          }
        }
      };
      lifecycle = lc;

      assertThatThrownBy(() -> lc.acquire(id)).isSameAs(seedFailure)
          .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(teardownFailure));

      // the storage was still removed: the retry is a genuine creation that runs onCreate again
      assertThat(lc.list()).doesNotContain(id);
      try (DatasetHandle ds = lc.acquire(id)) {
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }
      assertThat(seeds).hasValue(2);
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
  @DisplayName("maintenance of failed deletes")
  class Maintenance {

    @TempDir
    Path tmp;

    /**
     * A persistent lifecycle whose on-disk teardown of {@code id} fails for as long as
     * {@code failing} is set — the OS refusing to remove the storage, until the cause clears.
     */
    private DatasetLifecycleRdf4j failingFor(final Path root, final DatasetId id, final AtomicBoolean failing,
        final AtomicInteger seeds) {
      lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false), root,
          DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, (seeded, graphStore) -> seeds.incrementAndGet()) {
        @Override
        void deleteStorageOnDisk(final DatasetId toDelete) {
          if (toDelete.equals(id) && failing.get()) {
            throw new UncheckedIOException(new IOException("simulated disk failure"));
          }
          super.deleteStorageOnDisk(toDelete);
        }
      };
      return lifecycle;
    }

    @Test
    @DisplayName("the remains of a failed delete are enumerated, and only they")
    void listUnfinishedDeletes_namesTheRemains_butNotAnIntactDataset() {
      final Path root = tmp.resolve("stores");
      final DatasetId broken = new DatasetId("half-deleted");
      final DatasetId intact = new DatasetId("still-there");
      final DatasetLifecycleRdf4j lc = failingFor(root, broken, new AtomicBoolean(true), new AtomicInteger());
      lc.acquire(broken).close();
      lc.acquire(intact).close();
      assertThat(lc.listUnfinishedDeletes()).isEmpty(); // precondition: nothing has failed yet

      assertThatThrownBy(() -> lc.delete(broken)).isInstanceOf(UncheckedIOException.class);

      assertThat(lc.listUnfinishedDeletes()).containsExactly(broken);
      assertThat(lc.list()).containsExactly(intact); // the two listings do not overlap
    }

    @Test
    @DisplayName("the remains are still enumerated after a restart — the on-disk marker is what names them")
    void listUnfinishedDeletes_secondInstanceOverSameRoot_findsTheRemains() {
      final Path root = tmp.resolve("stores");
      final DatasetId id = new DatasetId("restart-listed");
      final DatasetLifecycleRdf4j first = failingFor(root, id, new AtomicBoolean(true), new AtomicInteger());
      first.acquire(id).close();
      assertThatThrownBy(() -> first.delete(id)).isInstanceOf(UncheckedIOException.class);
      first.shutDownAll(); // the process goes down with the remains still there

      final DatasetLifecycleRdf4j second = persistent(root);

      assertThat(second.listUnfinishedDeletes()).containsExactly(id);
    }

    @Test
    @DisplayName("clearing the remains creates nothing in their place, and the identifier is free again")
    void clearUnfinishedDelete_removesTheRemains_withoutCreatingADataset() throws Exception {
      final Path root = tmp.resolve("stores");
      final DatasetId id = new DatasetId("to-clear");
      final AtomicBoolean failing = new AtomicBoolean(true);
      final AtomicInteger seeds = new AtomicInteger();
      final DatasetLifecycleRdf4j lc = failingFor(root, id, failing, seeds);
      lc.acquire(id).close();
      assertThatThrownBy(() -> lc.delete(id)).isInstanceOf(UncheckedIOException.class);
      failing.set(false); // the cause — a locked file, a permission problem — has since cleared

      assertThat(lc.clearUnfinishedDelete(id)).isEqualTo(DatasetCleanupOutcome.CLEARED);

      // nothing was created in place of the remains: no directory, no seeding, in neither listing
      try (Stream<Path> children = Files.list(root)) {
        assertThat(children).isEmpty();
      }
      assertThat(seeds).hasValue(1); // only the original creation
      assertThat(lc.listUnfinishedDeletes()).isEmpty();
      assertThat(lc.list()).doesNotContain(id);
      // and the identifier is an ordinary unknown one again: acquire creates and seeds it afresh
      lc.acquire(id).close();
      assertThat(seeds).hasValue(2);
    }

    @Test
    @DisplayName("a cleanup that fails again reports the failure and leaves the identifier barred")
    void clearUnfinishedDelete_failsAgain_reportsTheFailureAndKeepsTheIdBarred() {
      final Path root = tmp.resolve("stores");
      final DatasetId id = new DatasetId("still-undeletable");
      final DatasetLifecycleRdf4j lc = failingFor(root, id, new AtomicBoolean(true), new AtomicInteger());
      lc.acquire(id).close();
      assertThatThrownBy(() -> lc.delete(id)).isInstanceOf(UncheckedIOException.class);

      assertThatThrownBy(() -> lc.clearUnfinishedDelete(id)).isInstanceOf(UncheckedIOException.class)
          .hasMessageContaining("simulated disk failure");

      assertThat(lc.listUnfinishedDeletes()).containsExactly(id);
      assertThatThrownBy(() -> lc.acquire(id)).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("unfinished delete");
    }

    @Test
    @DisplayName("clearing refuses an intact persisted dataset — getting rid of one is delete()")
    void clearUnfinishedDelete_intactPersistedDataset_isRefusedAndLeftAlone() {
      final Path root = tmp.resolve("stores");
      final DatasetLifecycleRdf4j lc = persistent(root);
      final DatasetId id = new DatasetId("intact");
      try (DatasetHandle ds = lc.acquire(id)) {
        ds.graphStore().add(GRAPH, singleTriple());
      }
      assertThat(lc.close(id)).isEqualTo(DatasetCloseOutcome.CLOSED); // on disk only, not held open

      assertThatThrownBy(() -> lc.clearUnfinishedDelete(id)).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("intact");

      try (DatasetHandle ds = lc.acquire(id)) {
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue(); // untouched
      }
    }

    @Test
    @DisplayName("clearing refuses a dataset that is open, leased or not")
    void clearUnfinishedDelete_openDataset_isRefused() {
      final DatasetLifecycleRdf4j lc = persistent(tmp.resolve("stores"));
      final DatasetId id = new DatasetId("open");

      try (DatasetHandle ds = lc.acquire(id)) {
        ds.graphStore().add(GRAPH, singleTriple());
        assertThatThrownBy(() -> lc.clearUnfinishedDelete(id)).isInstanceOf(IllegalStateException.class);
      }
      assertThatThrownBy(() -> lc.clearUnfinishedDelete(id)).isInstanceOf(IllegalStateException.class);

      try (DatasetHandle ds = lc.acquire(id)) {
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }
    }

    @Test
    @DisplayName("an identifier with neither a dataset nor remains reports that there was nothing to clear")
    void clearUnfinishedDelete_unknownId_reportsNothingToClear() {
      final DatasetLifecycleRdf4j lc = persistent(tmp.resolve("stores"));

      assertThat(lc.clearUnfinishedDelete(new DatasetId("never-seen")))
          .isEqualTo(DatasetCleanupOutcome.NOTHING_TO_CLEAR);
    }

    @Test
    @DisplayName("an in-memory lifecycle never has remains: nothing to list, nothing to clear, an open dataset refused")
    void inMemory_hasNoRemains() {
      final DatasetLifecycleRdf4j lc = inMemory();
      final DatasetId id = new DatasetId("in-memory");
      lc.acquire(id).close();

      assertThat(lc.listUnfinishedDeletes()).isEmpty();
      assertThatThrownBy(() -> lc.clearUnfinishedDelete(id)).isInstanceOf(IllegalStateException.class);
      lc.delete(id);
      assertThat(lc.clearUnfinishedDelete(id)).isEqualTo(DatasetCleanupOutcome.NOTHING_TO_CLEAR);
    }

    @Test
    @DisplayName("enumeration fails as a whole when the storage location cannot be read")
    void listUnfinishedDeletes_unreadableStorageRoot_fails() throws Exception {
      final Path root = tmp.resolve("stores");
      final DatasetLifecycleRdf4j lc = persistent(root);
      lc.acquire(new DatasetId("any")).close();
      final Set<PosixFilePermission> original = Files.getPosixFilePermissions(root);
      Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("-wx------"));
      try {
        assumeFalse(Files.isReadable(root), "running with privileges that ignore directory permissions");

        assertThatThrownBy(lc::listUnfinishedDeletes).isInstanceOf(UncheckedIOException.class);
      } finally {
        Files.setPosixFilePermissions(root, original);
      }
    }

    @Test
    @DisplayName("R2-5: a dataset whose deletion mark cannot be determined makes list() fail, not list it as normal")
    void list_undeterminableDeletionMark_fails() throws Exception {
      final Path root = tmp.resolve("stores");
      final DatasetLifecycleRdf4j lc = persistent(root);
      final DatasetId id = new DatasetId("unknown-mark");
      lc.acquire(id).close();
      final Path datasetDir = onlyChild(root);
      final Path marker = datasetDir.resolve(".deleting");
      final Set<PosixFilePermission> original = Files.getPosixFilePermissions(datasetDir);
      Files.setPosixFilePermissions(datasetDir, PosixFilePermissions.fromString("r--------"));
      try {
        assumeFalse(Files.exists(marker) || Files.notExists(marker),
            "running with privileges that ignore directory permissions");

        assertThatThrownBy(lc::list).isInstanceOf(UncheckedIOException.class);
      } finally {
        Files.setPosixFilePermissions(datasetDir, original);
      }
    }

    @Test
    @DisplayName("R2-1: an unreadable directory of an intact dataset is not read as new; acquire fails, data stays")
    void acquire_unreadableIntactDataset_doesNotDeleteIt() throws Exception {
      final Path root = tmp.resolve("stores");
      final AtomicInteger seeded = new AtomicInteger();
      lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false), root,
          DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, (i, graphStore) -> {
            seeded.incrementAndGet();
            graphStore.add(GRAPH, singleTriple());
          });
      final DatasetId id = new DatasetId("intact");
      lifecycle.acquire(id).close();
      lifecycle.shutDownAll();
      final Path datasetDir = onlyChild(root);
      final List<Path> dataFiles;
      try (Stream<Path> files = Files.list(datasetDir)) {
        dataFiles = files.toList();
      }
      assertThat(dataFiles).isNotEmpty();
      // the directory cannot be listed (no r), and the store files are unreadable so that init() fails too
      final Set<PosixFilePermission> original = Files.getPosixFilePermissions(datasetDir);
      final Map<Path, Set<PosixFilePermission>> originalFilePermissions = new HashMap<>();
      for (final Path file : dataFiles) {
        originalFilePermissions.put(file, Files.getPosixFilePermissions(file));
        Files.setPosixFilePermissions(file, Set.of());
      }
      Files.setPosixFilePermissions(datasetDir, PosixFilePermissions.fromString("-wx------"));
      try {
        assumeFalse(Files.isReadable(datasetDir), "running with privileges that ignore directory permissions");

        assertThatThrownBy(() -> lifecycle.acquire(id)).isInstanceOf(UncheckedIOException.class);
      } finally {
        Files.setPosixFilePermissions(datasetDir, original);
        originalFilePermissions.forEach((file, permissions) -> {
          try {
            Files.setPosixFilePermissions(file, permissions);
          } catch (final IOException e) {
            throw new UncheckedIOException(e);
          }
        });
      }

      // the failed attempt neither deleted nor marked the intact dataset: once readable again, it opens
      // as it was, without running onCreate a second time
      assertThat(dataFiles).allSatisfy(file -> assertThat(file).exists());
      assertThat(datasetDir.resolve(".deleting")).doesNotExist();
      try (DatasetHandle ds = lifecycle.acquire(id)) {
        assertThat(ds.sparqlQuery().ask(ASK_GRAPH)).isTrue();
      }
      assertThat(seeded).hasValue(1);
    }

    private static Path onlyChild(final Path root) throws IOException {
      try (Stream<Path> children = Files.list(root)) {
        return children.filter(Files::isDirectory).findFirst().orElseThrow();
      }
    }

    private DatasetLifecycleRdf4j persistent(final Path storageRoot) {
      lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false), storageRoot);
      return lifecycle;
    }
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("shutdown and listing")
  class ShutdownAndListing {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("R2-3: shutDownAll waits for a first acquire still creating its store; the id can be acquired again")
    void shutDownAll_duringFirstCreation_closesItAndAllowsReacquire() throws Exception {
      final Path root = tmp.resolve("stores");
      final CountDownLatch inHook = new CountDownLatch(1);
      final CountDownLatch releaseHook = new CountDownLatch(1);
      final AtomicBoolean firstCall = new AtomicBoolean(true);
      lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false), root,
          DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, (i, graphStore) -> {
            if (firstCall.getAndSet(false)) {
              inHook.countDown();
              awaitUninterruptibly(releaseHook);
            }
          });
      final DatasetId id = new DatasetId("in-flight");
      final ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        final Future<DatasetHandle> acquiring = pool.submit(() -> lifecycle.acquire(id));
        assertThat(inHook.await(30, TimeUnit.SECONDS)).isTrue();
        final Thread[] shutdownThread = new Thread[1];
        final CountDownLatch shutdownStarted = new CountDownLatch(1);
        final Future<?> shuttingDown = pool.submit(() -> {
          shutdownThread[0] = Thread.currentThread();
          shutdownStarted.countDown();
          lifecycle.shutDownAll();
        });
        assertThat(shutdownStarted.await(30, TimeUnit.SECONDS)).isTrue();
        // shutDownAll either waits for the creation (blocked/waiting) or — the defect — has already finished
        awaitBlockedOrDone(shutdownThread[0], shuttingDown);
        releaseHook.countDown();

        final DatasetHandle first = acquiring.get(30, TimeUnit.SECONDS);
        shuttingDown.get(30, TimeUnit.SECONDS);
        first.close();

        assertThat(lifecycle.acquire(id)).isNotNull().satisfies(DatasetHandle::close);
      } finally {
        releaseHook.countDown();
        pool.shutdownNow();
      }
    }

    @Test
    @DisplayName("R2-4: list() skips directories this lifecycle did not create (data/, logs/, non-UTF-8, non-canonical)")
    void list_skipsForeignDirectories() throws Exception {
      final Path root = tmp.resolve("stores");
      lifecycle = new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false), root,
          DatasetLifecycleRdf4j.DEFAULT_INDEX_SPEC, null);
      final DatasetId own = new DatasetId("own");
      lifecycle.acquire(own).close();
      lifecycle.close(own);
      Files.createDirectory(root.resolve("data"));
      Files.createDirectory(root.resolve("logs"));
      // Base64url of the single byte 0xFF: canonical, but not valid UTF-8
      Files.createDirectory(root.resolve("_w"));
      // Base64url of a blank value of three spaces: decodes to valid UTF-8 that DatasetId rejects
      Files.createDirectory(root.resolve("ICAg"));

      assertThat(lifecycle.list()).containsExactly(own);
    }

    private static void awaitUninterruptibly(final CountDownLatch latch) {
      try {
        latch.await(30, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private static void awaitBlockedOrDone(final Thread thread, final Future<?> done) {
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (!done.isDone() && thread.getState() == Thread.State.RUNNABLE && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
    }
  }

  @Nested
  @DisplayName("configuration")
  class Configuration {

    @Test
    @DisplayName("full-text search is rejected until #6")
    void fullTextSearch_rejected() {
      assertThatThrownBy(() -> new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.IN_MEMORY, true), null))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("#6");
    }
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("null arguments")
  class NullArguments {

    @Test
    @DisplayName("acquire(null) throws NullPointerException")
    void acquire_null_throws() {
      assertThatThrownBy(() -> inMemory().acquire(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("clearUnfinishedDelete(null) throws NullPointerException")
    void clearUnfinishedDelete_null_throws() {
      assertThatThrownBy(() -> inMemory().clearUnfinishedDelete(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("close(null) throws NullPointerException")
    void close_null_throws() {
      assertThatThrownBy(() -> inMemory().close(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("delete(null) throws NullPointerException")
    void delete_null_throws() {
      assertThatThrownBy(() -> inMemory().delete(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a null config throws NullPointerException")
    void constructor_nullConfig_throws() {
      assertThatThrownBy(() -> new DatasetLifecycleRdf4j(null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("PERSISTENT without a storage root throws NullPointerException")
    void constructor_persistentWithoutStorageRoot_throws() {
      assertThatThrownBy(() -> new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false), null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("PERSISTENT without an index spec throws NullPointerException")
    void constructor_persistentWithoutIndexSpec_throws(@TempDir final Path root) {
      assertThatThrownBy(
          () -> new DatasetLifecycleRdf4j(new DatasetStoreConfig(Persistence.PERSISTENT, false), root, null, null))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
