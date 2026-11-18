// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.rdf4j.RDF4JFactory;
import io.kogn.rdf.rdf4j.RDF4JIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Triple;

/**
 * Pins that a concurrent SPARQL {@code CLEAR ALL} / {@code DROP ALL} fails a transaction that read the
 * cleared data, on the {@code NativeStore} as on the {@code MemoryStore}. RDF4J 6.1.0's
 * {@code NativeStore} did not detect it (the dependent write committed); RDF4J 6.2.0-M2 does. If a
 * future RDF4J version regresses, this test fails and the Javadoc of {@link DatasetTransactorRdf4j}
 * and ARCHITECTURE.md must name the limit again. It also pins, on the {@code MemoryStore}, the effect
 * of a {@code CLEAR GRAPH} of another named graph: a read over all graphs conflicts, a read bound to
 * one graph commits.
 */
class ClearAllConflictDetectionTest {

  private static final IRI GRAPH = RDF4JIRI.of("https://example.org/graph/1");
  private static final IRI OTHER_GRAPH = RDF4JIRI.of("https://example.org/graph/2");
  private static final IRI PREMISE = RDF4JIRI.of("https://example.org/premise");
  private static final IRI DEPENDENT = RDF4JIRI.of("https://example.org/dependent");
  private static final IRI PREDICATE = RDF4JIRI.of("https://example.org/predicate");
  private static final IRI DEPENDS_ON = RDF4JIRI.of("https://example.org/dependsOn");

  private final RDF4JFactory rdf = new RDF4JFactory();

  @ParameterizedTest(name = "{0} guard, {1}")
  @MethodSource("guardsAndClears")
  @DisplayName("NativeStore: a concurrent CLEAR ALL / DROP ALL fails a transaction that read the cleared triple")
  void clearAll_concurrentWithGuardRead_onNativeStore(final String ignored, final Consumer<DatasetTx> guard,
      final String clear, @TempDir final Path tempDir) throws InterruptedException {
    final Throwable outcome = readThenConcurrentClear(new SailRepository(new NativeStore(tempDir.toFile())), guard,
        clear);

    assertThat(outcome).isInstanceOf(ConcurrencyConflictException.class);
  }

  @ParameterizedTest(name = "{0} guard, {1}")
  @MethodSource("guardsAndClears")
  @DisplayName("MemoryStore: a concurrent CLEAR ALL / DROP ALL fails a transaction that read the cleared triple")
  void clearAll_concurrentWithGuardRead_onMemoryStore(final String ignored, final Consumer<DatasetTx> guard,
      final String clear) throws InterruptedException {
    final Throwable outcome = readThenConcurrentClear(new SailRepository(new MemoryStore()), guard, clear);

    assertThat(outcome).isInstanceOf(ConcurrencyConflictException.class);
  }

  @Test
  @DisplayName("MemoryStore: a concurrent clear of another named graph fails a read over all graphs as a conflict")
  void clearOfOtherGraph_concurrentWithAllGraphsRead_onMemoryStore() throws InterruptedException {
    final Throwable outcome = readThenConcurrentClear(new SailRepository(new MemoryStore()),
        tx -> assertThat(tx.ask("ASK { GRAPH ?g { <" + PREMISE.getIRIString() + "> ?p ?o } }")).isTrue(),
        "CLEAR GRAPH <" + OTHER_GRAPH.getIRIString() + ">");

    assertThat(outcome).isInstanceOf(ConcurrencyConflictException.class);
  }

  @Test
  @DisplayName("MemoryStore: a concurrent clear of another named graph lets a read bound to one graph commit")
  void clearOfOtherGraph_concurrentWithBoundGraphRead_onMemoryStore() throws InterruptedException {
    final Throwable outcome = readThenConcurrentClear(new SailRepository(new MemoryStore()),
        tx -> assertThat(tx.contains(GRAPH, PREMISE, null, null)).isTrue(),
        "CLEAR GRAPH <" + OTHER_GRAPH.getIRIString() + ">");

    assertThat(outcome).isNull();
  }

  static Stream<Arguments> guardsAndClears() {
    final Map<String, Consumer<DatasetTx>> guards = Map.of("contains",
        tx -> assertThat(tx.contains(GRAPH, PREMISE, null, null)).isTrue(), "count",
        tx -> assertThat(tx.count(GRAPH)).isEqualTo(1L), "ask",
        tx -> assertThat(tx.ask("ASK { GRAPH <" + GRAPH.getIRIString() + "> { ?s ?p ?o } }")).isTrue());
    return guards.entrySet()
        .stream()
        .flatMap(guard -> Stream.of("CLEAR ALL", "DROP ALL")
            .map(clear -> Arguments.of(guard.getKey(), guard.getValue(), clear)));
  }

  /**
   * Seeds the graph with one premise triple, lets a transaction read it, then a second transaction
   * {@code CLEAR ALL} and commit, then the first writes a triple depending on the premise and commits;
   * returns what the first transaction ended with, {@code null} for a commit.
   */
  private Throwable readThenConcurrentClear(final Repository repository, final Consumer<DatasetTx> guard,
      final String clear) throws InterruptedException {
    repository.init();
    try {
      final DatasetTransactorRdf4j transactor = new DatasetTransactorRdf4j(repository);
      transactor.inTransaction(tx -> {
        tx.add(GRAPH, graphOf(rdf.createTriple(PREMISE, PREDICATE, rdf.createLiteral("p"))));
        tx.add(OTHER_GRAPH, graphOf(rdf.createTriple(DEPENDENT, PREDICATE, rdf.createLiteral("o"))));
        return null;
      });
      final CountDownLatch readDone = new CountDownLatch(1);
      final CountDownLatch clearCommitted = new CountDownLatch(1);
      final AtomicReference<Throwable> clearFailure = new AtomicReference<>();
      final Thread clearer = new Thread(() -> {
        try {
          readDone.await();
          transactor.inTransaction(tx -> {
            tx.update(clear);
            return null;
          });
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          clearFailure.set(e);
        } catch (RuntimeException e) {
          clearFailure.set(e);
        } finally {
          clearCommitted.countDown();
        }
      });
      clearer.start();

      Throwable outcome = null;
      try {
        transactor.inTransaction(tx -> {
          try {
            guard.accept(tx);
          } finally {
            readDone.countDown();
          }
          try {
            clearCommitted.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
          tx.add(GRAPH, graphOf(rdf.createTriple(DEPENDENT, DEPENDS_ON, PREMISE)));
          return null;
        });
      } catch (RuntimeException e) {
        outcome = e;
      }
      clearer.join();
      assertThat(clearFailure.get()).isNull();
      return outcome;
    } finally {
      repository.shutDown();
    }
  }

  private Graph graphOf(final Triple triple) {
    final Graph graph = rdf.createGraph();
    graph.add(triple);
    return graph;
  }
}
