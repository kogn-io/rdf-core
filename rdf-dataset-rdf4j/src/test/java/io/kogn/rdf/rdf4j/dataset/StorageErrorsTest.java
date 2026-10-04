// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.base.RepositoryConnectionWrapper;
import org.eclipse.rdf4j.repository.base.RepositoryWrapper;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.kogn.rdf.dataset.DatasetStorageException;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.SparqlEvaluationException;
import io.kogn.rdf.rdf4j.RDF4JFactory;
import io.kogn.rdf.rdf4j.RDF4JIRI;
import io.kogn.rdf.terms.Graph;

/**
 * Issue #153 (R1-6, R1-5): how storage failures of graph operations, and the use of a
 * {@link DatasetTx} after its transaction, reach the consumer.
 */
class StorageErrorsTest {

  private static final io.kogn.rdf.terms.IRI GRAPH = RDF4JIRI.of("https://example.org/graph/1");
  private static final io.kogn.rdf.terms.IRI SUBJECT = RDF4JIRI.of("https://example.org/subject");
  private static final io.kogn.rdf.terms.IRI PREDICATE = RDF4JIRI.of("https://example.org/predicate");
  private static final io.kogn.rdf.terms.IRI OBJECT = RDF4JIRI.of("https://example.org/object");

  private Repository repository;
  private RDF4JFactory rdf;

  @BeforeEach
  void setUp() {
    repository = new SailRepository(new MemoryStore());
    repository.init();
    rdf = new RDF4JFactory();
  }

  @AfterEach
  void tearDown() {
    repository.shutDown();
  }

  private Graph oneTriple() {
    final Graph graph = rdf.createGraph();
    graph.add(rdf.createTriple(SUBJECT, PREDICATE, OBJECT));
    return graph;
  }

  /** A backend failure that is no RDF4J type, as an inferencer or federation sail may throw it. */
  private static final class ForeignFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ForeignFailure(final String message) {
      super(message);
    }
  }

  /** Fails every data access of the connection with the given failure; commit and close work. */
  private Repository failingWith(final RuntimeException failure) {
    return new RepositoryWrapper(repository) {
      @Override
      public RepositoryConnection getConnection() {
        return new RepositoryConnectionWrapper(this, super.getConnection()) {
          @Override
          public void add(final Resource s, final IRI p, final Value o, final Resource... contexts) {
            throw failure;
          }

          @Override
          public void remove(final Resource s, final IRI p, final Value o, final Resource... contexts) {
            throw failure;
          }

          @Override
          public void clear(final Resource... contexts) {
            throw failure;
          }

          @Override
          public long size(final Resource... contexts) {
            throw failure;
          }

          @Override
          public RepositoryResult<Statement> getStatements(final Resource s, final IRI p, final Value o,
              final boolean inferred, final Resource... contexts) {
            throw failure;
          }

          @Override
          public boolean hasStatement(final Resource s, final IRI p, final Value o, final boolean inferred,
              final Resource... contexts) {
            throw failure;
          }
        };
      }
    };
  }

  /** Lets the data access work but fails the commit. */
  private Repository failingCommit(final RuntimeException failure) {
    return new RepositoryWrapper(repository) {
      @Override
      public RepositoryConnection getConnection() {
        return new RepositoryConnectionWrapper(this, super.getConnection()) {
          @Override
          public void commit() {
            throw failure;
          }
        };
      }
    };
  }

  static java.util.stream.Stream<RuntimeException> backendFailures() {
    return java.util.stream.Stream.of(new RepositoryException("disk full"), new ForeignFailure("sail broke"));
  }

  @ParameterizedTest
  @MethodSource("backendFailures")
  @DisplayName("every GraphStore operation surfaces a backend failure as the neutral DatasetStorageException")
  void graphStore_backendFailure_isTranslated(final RuntimeException failure) {
    final GraphStoreRdf4j store = new GraphStoreRdf4j(failingWith(failure));
    final Graph triples = oneTriple();

    for (final Consumer<GraphStoreRdf4j> operation : java.util.List.<Consumer<GraphStoreRdf4j>>of(
        s -> s.add(GRAPH, triples), s -> s.remove(GRAPH, triples), s -> s.clear(GRAPH), s -> s.export(GRAPH),
        s -> s.count(GRAPH), GraphStoreRdf4j::count)) {
      assertThatThrownBy(() -> operation.accept(store)).isInstanceOf(DatasetStorageException.class).hasCause(failure);
    }
  }

  @ParameterizedTest
  @MethodSource("backendFailures")
  @DisplayName("every graph operation of a DatasetTx, contains included, surfaces a backend failure as the neutral"
      + " DatasetStorageException")
  void datasetTx_backendFailure_isTranslated(final RuntimeException failure) {
    final DatasetTransactorRdf4j transactor = new DatasetTransactorRdf4j(failingWith(failure));
    final Graph triples = oneTriple();

    for (final Consumer<DatasetTx> operation : java.util.List.<Consumer<DatasetTx>>of(tx -> tx.add(GRAPH, triples),
        tx -> tx.remove(GRAPH, triples), tx -> tx.clear(GRAPH), tx -> tx.export(GRAPH), tx -> tx.count(GRAPH),
        DatasetTx::count, tx -> tx.contains(GRAPH, SUBJECT, PREDICATE, OBJECT))) {
      assertThatThrownBy(() -> transactor.inTransaction(tx -> {
        operation.accept(tx);
        return null;
      })).isInstanceOf(DatasetStorageException.class).hasCause(failure);
    }
  }

  @Test
  @DisplayName("a failing connection pool surfaces as DatasetStorageException on both ports")
  void getConnectionFails_isTranslated() {
    final RepositoryException failure = new RepositoryException("repository is shut down");
    final Repository noConnection = new RepositoryWrapper(repository) {
      @Override
      public RepositoryConnection getConnection() {
        throw failure;
      }
    };

    assertThatThrownBy(() -> new GraphStoreRdf4j(noConnection).count()).isInstanceOf(DatasetStorageException.class)
        .hasCause(failure);
    assertThatThrownBy(() -> new DatasetTransactorRdf4j(noConnection).inTransaction(tx -> null))
        .isInstanceOf(DatasetStorageException.class)
        .hasCause(failure);
  }

  @ParameterizedTest
  @MethodSource("backendFailures")
  @DisplayName("a failed commit that is not a conflict surfaces as DatasetStorageException")
  void commitFails_isTranslated(final RuntimeException failure) {
    final DatasetTransactorRdf4j transactor = new DatasetTransactorRdf4j(failingCommit(failure));

    assertThatThrownBy(() -> transactor.inTransaction(tx -> tx.add(GRAPH, oneTriple())))
        .isInstanceOf(DatasetStorageException.class)
        .hasCause(failure);
    assertThat(new GraphStoreRdf4j(repository).count(GRAPH)).isZero();
  }

  @Test
  @DisplayName("a failure of the caller's own work function passes through unchanged")
  void workFunctionFailure_isNotTranslated() {
    final RepositoryException own = new RepositoryException("thrown by the caller");

    assertThatThrownBy(() -> new DatasetTransactorRdf4j(repository).inTransaction(tx -> {
      throw own;
    })).isSameAs(own);
  }

  @Test
  @DisplayName("a null argument is a defect of the call: NullPointerException, not DatasetStorageException")
  void nullArgument_isNotTranslated() {
    assertThatThrownBy(() -> new GraphStoreRdf4j(repository).add(null, oneTriple()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new GraphStoreRdf4j(repository).add(GRAPH, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("every operation of a DatasetTx used after its transaction ended fails the same way:"
      + " IllegalStateException, never SparqlEvaluationException or DatasetStorageException (R1-5)")
  void escapedTx_everyOperationThrowsIllegalStateException() {
    final DatasetTransactorRdf4j transactor = new DatasetTransactorRdf4j(repository);
    final AtomicReference<DatasetTx> escaped = new AtomicReference<>();
    transactor.inTransaction(tx -> {
      escaped.set(tx);
      return null;
    });
    final DatasetTx tx = escaped.get();
    final Graph triples = oneTriple();

    for (final Consumer<DatasetTx> operation : java.util.List.<Consumer<DatasetTx>>of(t -> t.add(GRAPH, triples),
        t -> t.remove(GRAPH, triples), t -> t.clear(GRAPH), t -> t.export(GRAPH), t -> t.count(GRAPH), DatasetTx::count,
        t -> t.contains(GRAPH, SUBJECT, PREDICATE, OBJECT), t -> t.update("CLEAR ALL"),
        t -> t.update("CLEAR ALL", Map.of()), t -> t.select("SELECT * WHERE { ?s ?p ?o }"),
        t -> t.select("SELECT * WHERE { ?s ?p ?o }", Map.of()), t -> t.ask("ASK { ?s ?p ?o }"),
        t -> t.ask("ASK { ?s ?p ?o }", Map.of()), t -> t.construct("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"),
        t -> t.construct("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", Map.of()))) {
      assertThatThrownBy(() -> operation.accept(tx)).isExactlyInstanceOf(IllegalStateException.class)
          .isNotInstanceOf(SparqlEvaluationException.class);
    }
  }

  @Test
  @DisplayName("a DatasetTx is dead after a rollback as well")
  void escapedTx_afterRollback_isDead() {
    final AtomicReference<DatasetTx> escaped = new AtomicReference<>();

    assertThatThrownBy(() -> new DatasetTransactorRdf4j(repository).inTransaction(tx -> {
      escaped.set(tx);
      throw new IllegalArgumentException("work failed");
    })).isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> escaped.get().count()).isExactlyInstanceOf(IllegalStateException.class);
  }
}
