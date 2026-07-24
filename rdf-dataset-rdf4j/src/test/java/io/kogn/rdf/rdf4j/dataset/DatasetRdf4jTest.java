// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.base.RepositoryConnectionWrapper;
import org.eclipse.rdf4j.repository.base.RepositoryWrapper;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.SailConflictException;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.MalformedSparqlException;
import io.kogn.rdf.rdf4j.RDF4JFactory;
import io.kogn.rdf.rdf4j.RDF4JIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * Tests for the RDF4J dataset port implementations.
 *
 * <p>Uses an in-memory RDF4J store; no Spring context required.
 * Follows the same setUp/tearDown pattern as {@code CollectionStoreRdf4jTest}.</p>
 */
class DatasetRdf4jTest {

  private Repository repository;
  private RDF4JFactory rdf;

  private static final IRI GRAPH_1 = RDF4JIRI.of("https://example.org/graph/1");
  private static final IRI GRAPH_2 = RDF4JIRI.of("https://example.org/graph/2");
  private static final IRI SUBJECT = RDF4JIRI.of("https://example.org/subject");
  private static final IRI PREDICATE = RDF4JIRI.of("https://example.org/predicate");
  private static final IRI OBJECT = RDF4JIRI.of("https://example.org/object");

  @BeforeEach
  void setUp() {
    repository = new SailRepository(new MemoryStore());
    repository.init();
    rdf = new RDF4JFactory();
  }

  @AfterEach
  void tearDown() {
    if (repository != null) {
      repository.shutDown();
    }
  }

  private Graph singleTripleGraph() {
    final Graph graph = rdf.createGraph();
    graph.add(rdf.createTriple(SUBJECT, PREDICATE, OBJECT));
    return graph;
  }

  private Graph valueTriple(final String value) {
    final Graph graph = rdf.createGraph();
    graph.add(rdf.createTriple(SUBJECT, PREDICATE, rdf.createLiteral(value)));
    return graph;
  }

  private void awaitUninterruptibly(final CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private void awaitUninterruptibly(final CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (BrokenBarrierException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The two Sail implementations {@link DatasetLifecycleRdf4j} actually ships
   * ({@code IN_MEMORY} / {@code PERSISTENT}) — used to parameterize the tests whose guarantees are
   * store-specific (isolation, conflict detection) rather than plain functional behaviour, which is
   * identical across Sails via the shared {@code RepositoryConnection} API.
   */
  private enum Backend {
    MEMORY {
      @Override
      Repository create(final Path tempDir) {
        return new SailRepository(new MemoryStore());
      }
    },
    NATIVE {
      @Override
      Repository create(final Path tempDir) {
        return new SailRepository(new NativeStore(tempDir.toFile()));
      }
    };

    abstract Repository create(Path tempDir);
  }

  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("GraphStoreRdf4j")
  class GraphStoreTests {

    private GraphStoreRdf4j store;

    @BeforeEach
    void setUp() {
      store = new GraphStoreRdf4j(repository);
    }

    @Test
    @DisplayName("add increases count in named graph")
    void add_singleTriple_countIsOne() {
      // given
      final Graph graph = singleTripleGraph();

      // when
      store.add(GRAPH_1, graph);

      // then
      assertThat(store.count(GRAPH_1)).isEqualTo(1L);
    }

    @Test
    @DisplayName("add returns the net number of triples inserted")
    void add_singleTriple_returnsOne() {
      // when / then
      assertThat(store.add(GRAPH_1, singleTripleGraph())).isEqualTo(1L);
    }

    @Test
    @DisplayName("add returns 0 when all triples are already present (idempotent)")
    void add_duplicate_returnsZero() {
      // given
      store.add(GRAPH_1, singleTripleGraph());

      // when / then
      assertThat(store.add(GRAPH_1, singleTripleGraph())).isEqualTo(0L);
    }

    @Test
    @DisplayName("remove returns the net number of triples removed")
    void remove_existingTriple_returnsOne() {
      // given
      store.add(GRAPH_1, singleTripleGraph());

      // when / then
      assertThat(store.remove(GRAPH_1, singleTripleGraph())).isEqualTo(1L);
    }

    @Test
    @DisplayName("remove returns 0 when no triple was present")
    void remove_absentTriple_returnsZero() {
      // when / then
      assertThat(store.remove(GRAPH_1, singleTripleGraph())).isEqualTo(0L);
    }

    @Test
    @DisplayName("export returns the added triples")
    void export_afterAdd_returnsTriples() {
      // given
      store.add(GRAPH_1, singleTripleGraph());

      // when
      final ReadableGraph exported = store.export(GRAPH_1);

      // then
      assertThat(exported.size()).isEqualTo(1L);
    }

    @Test
    @DisplayName("remove deletes the specified triples")
    void remove_afterAdd_triplesAreGone() {
      // given
      final Graph graph = singleTripleGraph();
      store.add(GRAPH_1, graph);

      // when
      store.remove(GRAPH_1, graph);

      // then
      assertThat(store.count(GRAPH_1)).isEqualTo(0L);
    }

    @Test
    @DisplayName("clear empties the named graph")
    void clear_afterAdd_graphIsEmpty() {
      // given
      store.add(GRAPH_1, singleTripleGraph());

      // when
      store.clear(GRAPH_1);

      // then
      assertThat(store.count(GRAPH_1)).isEqualTo(0L);
    }

    @Test
    @DisplayName("count() returns total across all named graphs")
    void count_acrossMultipleGraphs_returnsTotal() {
      // given
      store.add(GRAPH_1, singleTripleGraph());
      store.add(GRAPH_2, singleTripleGraph());

      // when / then
      assertThat(store.count()).isEqualTo(2L);
    }

    @Test
    @DisplayName("count per graph is isolated from other graphs")
    void count_perGraph_isIsolated() {
      // given
      store.add(GRAPH_1, singleTripleGraph());

      // when / then
      assertThat(store.count(GRAPH_1)).isEqualTo(1L);
      assertThat(store.count(GRAPH_2)).isEqualTo(0L);
    }
  }

  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("SparqlUpdateRdf4j")
  class SparqlUpdateTests {

    private SparqlUpdateRdf4j sparqlUpdate;
    private SparqlQueryRdf4j sparqlQuery;

    @BeforeEach
    void setUp() {
      sparqlUpdate = new SparqlUpdateRdf4j(repository);
      sparqlQuery = new SparqlQueryRdf4j(repository);
    }

    @Test
    @DisplayName("INSERT DATA makes triple visible")
    void update_insertData_tripleIsVisible() {
      // given
      final String insert = "INSERT DATA { GRAPH <" + GRAPH_1.getIRIString() + "> { <" + SUBJECT.getIRIString() + "> <"
          + PREDICATE.getIRIString() + "> <" + OBJECT.getIRIString() + "> } }";
      final String ask = "ASK { GRAPH <" + GRAPH_1.getIRIString() + "> { <" + SUBJECT.getIRIString() + "> <"
          + PREDICATE.getIRIString() + "> <" + OBJECT.getIRIString() + "> } }";

      // when
      sparqlUpdate.update(insert);

      // then
      assertThat(sparqlQuery.ask(ask)).isTrue();
    }

    @Test
    @DisplayName("DELETE DATA removes the triple")
    void update_deleteData_tripleIsGone() {
      // given
      final String graphIri = GRAPH_1.getIRIString();
      final String triple = " <" + SUBJECT.getIRIString() + "> <" + PREDICATE.getIRIString() + "> <"
          + OBJECT.getIRIString() + "> ";
      sparqlUpdate.update("INSERT DATA { GRAPH <" + graphIri + "> {" + triple + "} }");

      // when
      sparqlUpdate.update("DELETE DATA { GRAPH <" + graphIri + "> {" + triple + "} }");

      // then
      assertThat(sparqlQuery.ask("ASK { GRAPH <" + graphIri + "> { ?s ?p ?o } }")).isFalse();
    }

    @Test
    @DisplayName("update — malformed SPARQL fails with the neutral MalformedSparqlException, not a backend type")
    void update_malformedSparql_throwsNeutralException() {
      // given — a syntactically broken update. The ports document this as a parse failure; the
      // backend's MalformedQueryException must not leak, and the type must not be the
      // IllegalArgumentException the Javadoc used to (wrongly) name — see issue #31.
      assertThatThrownBy(() -> sparqlUpdate.update("INSERT DATA { this is not sparql"))
          .isInstanceOf(MalformedSparqlException.class)
          .isNotInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(MalformedQueryException.class);
    }
  }

  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("SparqlQueryRdf4j")
  class SparqlQueryTests {

    private SparqlUpdateRdf4j sparqlUpdate;
    private SparqlQueryRdf4j sparqlQuery;

    @BeforeEach
    void setUp() {
      sparqlUpdate = new SparqlUpdateRdf4j(repository);
      sparqlQuery = new SparqlQueryRdf4j(repository);
    }

    private void insertSingleTriple() {
      sparqlUpdate.update("INSERT DATA { GRAPH <" + GRAPH_1.getIRIString() + "> { <" + SUBJECT.getIRIString() + "> <"
          + PREDICATE.getIRIString() + "> <" + OBJECT.getIRIString() + "> } }");
    }

    @Test
    @DisplayName("ask returns true when the pattern matches")
    void ask_match_returnsTrue() {
      // given
      insertSingleTriple();

      // when / then
      assertThat(sparqlQuery.ask("ASK { GRAPH <" + GRAPH_1.getIRIString() + "> { ?s ?p ?o } }")).isTrue();
    }

    @Test
    @DisplayName("ask returns false when the pattern has no match")
    void ask_noMatch_returnsFalse() {
      // when / then
      assertThat(sparqlQuery.ask("ASK { GRAPH <" + GRAPH_1.getIRIString() + "> { ?s ?p ?o } }")).isFalse();
    }

    @Test
    @DisplayName("select returns one binding set per matching row")
    void select_matchingPattern_returnsRows() {
      // given
      insertSingleTriple();

      // when
      final List<BindingSet> results = sparqlQuery.select("SELECT ?s WHERE { GRAPH <" + GRAPH_1.getIRIString()
          + "> { ?s <" + PREDICATE.getIRIString() + "> <" + OBJECT.getIRIString() + "> } }").toList();

      // then
      assertThat(results).hasSize(1);
      assertThat(results.get(0).getValue("s"))
          .hasValueSatisfying(term -> assertThat(((IRI) term).getIRIString()).isEqualTo(SUBJECT.getIRIString()));
    }

    @Test
    @DisplayName("select stream is consumable after the call returns")
    void select_streamMaterialised_consumableAfterReturn() {
      // given
      insertSingleTriple();

      // when
      final Stream<BindingSet> stream = sparqlQuery
          .select("SELECT ?s WHERE { GRAPH <" + GRAPH_1.getIRIString() + "> { ?s ?p ?o } }");

      // then — connection already closed, stream still consumable
      assertThat(stream.toList()).hasSize(1);
    }

    @Test
    @DisplayName("construct returns a graph with the matching triples")
    void construct_matchingPattern_returnsGraph() {
      // given
      insertSingleTriple();

      // when
      final ReadableGraph constructed = sparqlQuery
          .construct("CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + GRAPH_1.getIRIString() + "> { ?s ?p ?o } }");

      // then
      assertThat(constructed.size()).isEqualTo(1L);
    }

    @Test
    @DisplayName("select — malformed SPARQL fails with the neutral MalformedSparqlException")
    void select_malformedSparql_throwsNeutralException() {
      assertThatThrownBy(() -> sparqlQuery.select("SELECT ?s WHERE {{{").toList())
          .isInstanceOf(MalformedSparqlException.class)
          .isNotInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(MalformedQueryException.class);
    }

    @Test
    @DisplayName("construct — malformed SPARQL fails with the neutral MalformedSparqlException")
    void construct_malformedSparql_throwsNeutralException() {
      assertThatThrownBy(() -> sparqlQuery.construct("CONSTRUCT WHERE not valid"))
          .isInstanceOf(MalformedSparqlException.class)
          .isNotInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(MalformedQueryException.class);
    }

    @Test
    @DisplayName("ask — malformed SPARQL fails with the neutral MalformedSparqlException")
    void ask_malformedSparql_throwsNeutralException() {
      assertThatThrownBy(() -> sparqlQuery.ask("ASK { this is not sparql")).isInstanceOf(MalformedSparqlException.class)
          .isNotInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(MalformedQueryException.class);
    }
  }

  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("DatasetTransactorRdf4j")
  class DatasetTransactorTests {

    private DatasetTransactorRdf4j transactor;
    private GraphStoreRdf4j store;

    @BeforeEach
    void setUp() {
      transactor = new DatasetTransactorRdf4j(repository);
      store = new GraphStoreRdf4j(repository);
    }

    @Test
    @DisplayName("commit path — mutations visible after inTransaction")
    void inTransaction_commit_mutationsArePersisted() {
      // given
      final Graph graph = singleTripleGraph();

      // when
      transactor.inTransaction(tx -> {
        tx.add(GRAPH_1, graph);
        return null;
      });

      // then
      assertThat(store.count(GRAPH_1)).isEqualTo(1L);
    }

    @Test
    @DisplayName("rollback — exception in work lambda rolls back all mutations")
    void inTransaction_exceptionInWork_rollsBack() {
      // given
      final Graph graph = singleTripleGraph();

      // when
      assertThatThrownBy(() -> transactor.inTransaction(tx -> {
        tx.add(GRAPH_1, graph);
        throw new RuntimeException("deliberate failure");
      })).isInstanceOf(RuntimeException.class).hasMessage("deliberate failure");

      // then
      assertThat(store.count(GRAPH_1)).isEqualTo(0L);
    }

    @Test
    @DisplayName("nested inTransaction on the same thread is rejected")
    void inTransaction_nestedCall_throwsIllegalStateException() {
      // given — the port forbids nesting (see DatasetTransactor Javadoc); a nested call on the
      // same thread must be rejected loudly rather than silently opening a second, independent
      // transaction.
      // when, then
      assertThatThrownBy(() -> transactor.inTransaction(tx -> transactor.inTransaction(innerTx -> null)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("nested transactions are not supported");
    }

    @Test
    @DisplayName("the nesting guard is cleared after the outer transaction throws, so the next call succeeds")
    void inTransaction_afterExceptionInOuter_guardIsClearedForNextCall() {
      // given — a previous call failed; the ThreadLocal guard must still be cleared in `finally`
      // so this thread is not permanently locked out of future transactions.
      assertThatThrownBy(() -> transactor.inTransaction(tx -> {
        throw new RuntimeException("deliberate failure");
      })).isInstanceOf(RuntimeException.class);

      // when, then — a normal, non-nested transaction on the same thread still works
      final Graph graph = singleTripleGraph();
      transactor.inTransaction(tx -> {
        tx.add(GRAPH_1, graph);
        return null;
      });
      assertThat(store.count(GRAPH_1)).isEqualTo(1L);
    }

    @Test
    @DisplayName("sequential (non-nested) transactions on the same thread both succeed")
    void inTransaction_sequentialCalls_bothSucceed() {
      // given, when
      transactor.inTransaction(tx -> {
        tx.add(GRAPH_1, valueTriple("value-1"));
        return null;
      });
      transactor.inTransaction(tx -> {
        tx.add(GRAPH_1, valueTriple("value-2"));
        return null;
      });

      // then
      assertThat(store.count(GRAPH_1)).isEqualTo(2L);
    }

    @Test
    @DisplayName("a transaction on a different dataset nested on the same thread is allowed")
    void inTransaction_nestedOnDifferentTransactor_isAllowed() {
      // given — the guard is per transactor instance, not process-wide: being inside a
      // transaction on one transactor (dataset) must not block an independent transaction on
      // another. Only self-nesting on the same transactor is forbidden.
      final Repository otherRepository = new SailRepository(new MemoryStore());
      otherRepository.init();
      try {
        final DatasetTransactorRdf4j otherTransactor = new DatasetTransactorRdf4j(otherRepository);
        final GraphStoreRdf4j otherStore = new GraphStoreRdf4j(otherRepository);

        // when
        transactor.inTransaction(outer -> otherTransactor.inTransaction(inner -> {
          inner.add(GRAPH_1, singleTripleGraph());
          return null;
        }));

        // then
        assertThat(otherStore.count(GRAPH_1)).isEqualTo(1L);
      } finally {
        otherRepository.shutDown();
      }
    }

    @Test
    @DisplayName("a failure in the work lambda is not reported as a concurrency conflict")
    void inTransaction_exceptionInWork_isNotTranslatedToConflict() {
      // given — a bug in the caller's own work function. It must stay distinguishable from a lost
      // race, otherwise a retry loop catching conflicts would spin forever on a programming error.
      // when, then
      assertThatThrownBy(() -> transactor.inTransaction(tx -> {
        throw new NullPointerException("bug in the caller's work");
      })).isInstanceOf(NullPointerException.class).isNotInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    @DisplayName("a commit failure that is not a conflict passes through untranslated")
    void inTransaction_nonConflictCommitFailure_isNotTranslatedToConflict() {
      // given — a repository whose commit fails for a reason unrelated to a lost race. Only a
      // SailConflictException in the cause chain may become a ConcurrencyConflictException:
      // translating anything else would make a retry loop spin on a permanent failure.
      final Repository failingCommit = new RepositoryWrapper(repository) {
        @Override
        public RepositoryConnection getConnection() {
          return new RepositoryConnectionWrapper(this, super.getConnection()) {
            @Override
            public void commit() {
              throw new RepositoryException("storage failure, not a conflict");
            }
          };
        }
      };

      // when, then
      assertThatThrownBy(() -> new DatasetTransactorRdf4j(failingCommit).inTransaction(tx -> null))
          .isInstanceOf(RepositoryException.class)
          .hasMessage("storage failure, not a conflict")
          .isNotInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    @DisplayName("read-your-writes — select sees triples added in the same transaction")
    void inTransaction_selectAfterAdd_seesUncommittedTriples() {
      // given
      final Graph graph = singleTripleGraph();

      // when
      final List<BindingSet> results = transactor.inTransaction(tx -> {
        tx.add(GRAPH_1, graph);
        return tx.select("SELECT ?s WHERE { GRAPH <" + GRAPH_1.getIRIString() + "> { ?s <" + PREDICATE.getIRIString()
            + "> <" + OBJECT.getIRIString() + "> } }").toList();
      });

      // then
      assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("read-your-writes — ask returns true for triple added in the same transaction")
    void inTransaction_askAfterAdd_returnsTrue() {
      // given
      final Graph graph = singleTripleGraph();

      // when
      final boolean found = transactor.inTransaction(tx -> {
        tx.add(GRAPH_1, graph);
        return tx.ask("ASK { GRAPH <" + GRAPH_1.getIRIString() + "> { <" + SUBJECT.getIRIString() + "> ?p ?o } }");
      });

      // then
      assertThat(found).isTrue();
    }

    @Test
    @DisplayName("read-your-writes — construct returns graph with triples added in the same transaction")
    void inTransaction_constructAfterAdd_returnsGraph() {
      // given
      final Graph graph = singleTripleGraph();

      // when
      final ReadableGraph constructed = transactor.inTransaction(tx -> {
        tx.add(GRAPH_1, graph);
        return tx.construct("CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + GRAPH_1.getIRIString() + "> { ?s ?p ?o } }");
      });

      // then
      assertThat(constructed.size()).isEqualTo(1L);
    }

    @Test
    @DisplayName("read-your-writes — SPARQL UPDATE within transaction visible via ask in same transaction")
    void inTransaction_sparqlUpdateThenAsk_seesUpdate() {
      // when
      final boolean found = transactor.inTransaction(tx -> {
        tx.update("INSERT DATA { GRAPH <" + GRAPH_1.getIRIString() + "> { <" + SUBJECT.getIRIString() + "> <"
            + PREDICATE.getIRIString() + "> <" + OBJECT.getIRIString() + "> } }");
        return tx.ask("ASK { GRAPH <" + GRAPH_1.getIRIString() + "> { ?s ?p ?o } }");
      });

      // then
      assertThat(found).isTrue();
    }

    @Test
    @DisplayName("contains — exact triple pattern matches")
    void contains_exactPattern_returnsTrue() {
      // given
      store.add(GRAPH_1, singleTripleGraph());

      // when
      final boolean found = transactor.inTransaction(tx -> tx.contains(GRAPH_1, SUBJECT, PREDICATE, OBJECT));

      // then
      assertThat(found).isTrue();
    }

    @Test
    @DisplayName("contains — absent pattern does not match")
    void contains_absentPattern_returnsFalse() {
      // given
      store.add(GRAPH_1, singleTripleGraph());

      // when
      final boolean found = transactor
          .inTransaction(tx -> tx.contains(GRAPH_1, SUBJECT, PREDICATE, rdf.createLiteral("other")));

      // then
      assertThat(found).isFalse();
    }

    @Test
    @DisplayName("contains — null matches any component")
    void contains_nullWildcards_match() {
      // given
      store.add(GRAPH_1, singleTripleGraph());

      // when
      final boolean objectWildcard = transactor.inTransaction(tx -> tx.contains(GRAPH_1, SUBJECT, PREDICATE, null));
      final boolean subjectWildcard = transactor.inTransaction(tx -> tx.contains(GRAPH_1, null, PREDICATE, null));
      final boolean allWildcards = transactor.inTransaction(tx -> tx.contains(GRAPH_1, null, null, null));

      // then
      assertThat(objectWildcard).isTrue();
      assertThat(subjectWildcard).isTrue();
      assertThat(allWildcards).isTrue();
    }

    @Test
    @DisplayName("contains — is scoped to the named graph")
    void contains_otherNamedGraph_returnsFalse() {
      // given
      store.add(GRAPH_1, singleTripleGraph());

      // when
      final boolean found = transactor.inTransaction(tx -> tx.contains(GRAPH_2, SUBJECT, PREDICATE, OBJECT));

      // then
      assertThat(found).isFalse();
    }

    @Test
    @DisplayName("read-your-writes — contains sees a triple added in the same transaction")
    void inTransaction_containsAfterAdd_returnsTrue() {
      // given
      final Graph graph = singleTripleGraph();

      // when
      final boolean found = transactor.inTransaction(tx -> {
        tx.add(GRAPH_1, graph);
        return tx.contains(GRAPH_1, SUBJECT, PREDICATE, null);
      });

      // then
      assertThat(found).isTrue();
    }

    @Test
    @DisplayName("tx.update — malformed SPARQL fails with the neutral MalformedSparqlException")
    void inTransaction_updateMalformedSparql_throwsNeutralException() {
      assertThatThrownBy(() -> transactor.inTransaction(tx -> {
        tx.update("INSERT DATA { this is not sparql");
        return null;
      })).isInstanceOf(MalformedSparqlException.class)
          .isNotInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(MalformedQueryException.class);
    }

    @Test
    @DisplayName("tx.select — malformed SPARQL fails with the neutral MalformedSparqlException")
    void inTransaction_selectMalformedSparql_throwsNeutralException() {
      assertThatThrownBy(() -> transactor.inTransaction(tx -> tx.select("SELECT ?s WHERE {{{").toList()))
          .isInstanceOf(MalformedSparqlException.class)
          .isNotInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(MalformedQueryException.class);
    }

    @Test
    @DisplayName("tx.ask — malformed SPARQL fails with the neutral MalformedSparqlException")
    void inTransaction_askMalformedSparql_throwsNeutralException() {
      assertThatThrownBy(() -> transactor.inTransaction(tx -> tx.ask("ASK { this is not sparql")))
          .isInstanceOf(MalformedSparqlException.class)
          .isNotInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(MalformedQueryException.class);
    }

    @Test
    @DisplayName("tx.construct — malformed SPARQL fails with the neutral MalformedSparqlException")
    void inTransaction_constructMalformedSparql_throwsNeutralException() {
      assertThatThrownBy(() -> transactor.inTransaction(tx -> tx.construct("CONSTRUCT WHERE not valid")))
          .isInstanceOf(MalformedSparqlException.class)
          .isNotInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(MalformedQueryException.class);
    }
  }

  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("backend divergence — MemoryStore vs NativeStore")
  class BackendDivergenceTests {

    /**
     * Two triples that make GRAPH_1, SUBJECT and PREDICATE known to the store without satisfying
     * {@code ASK { GRAPH GRAPH_1 { SUBJECT PREDICATE ?o } }}.
     */
    private Graph seedTriples() {
      final Graph graph = rdf.createGraph();
      graph
          .add(rdf.createTriple(SUBJECT, RDF4JIRI.of("https://example.org/seed-predicate"), rdf.createLiteral("seed")));
      graph
          .add(rdf.createTriple(RDF4JIRI.of("https://example.org/seed-subject"), PREDICATE, rdf.createLiteral("seed")));
      return graph;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Backend.class)
    @DisplayName("add returns the exact delta despite a concurrent commit to the same named graph"
        + " between the before/after size samples")
    void add_concurrentCommitToSameGraphBetweenSamples_returnsExactDelta(final Backend backend,
        @TempDir final Path tempDir) throws InterruptedException {
      // given — a connection wrapper that pauses right after the first size() call inside
      // GraphStoreRdf4j#add (the "before" sample) so a second thread can commit an unrelated
      // triple to the same named graph before the "after" sample runs. The interleave is forced
      // by latches, not by hoping a timing window is hit — this repo's tests treat wall-clock
      // races as flaky by nature (issue #22/#23) and require a deterministic reproduction instead.
      // Verified against both backends per #52: the delta-race fix (#32/#47) was originally proven
      // only against MemoryStore.
      final Repository backendRepository = backend.create(tempDir);
      backendRepository.init();
      try {
        final GraphStoreRdf4j backendStore = new GraphStoreRdf4j(backendRepository);
        final CountDownLatch beforeSampleTaken = new CountDownLatch(1);
        final CountDownLatch concurrentWriteCommitted = new CountDownLatch(1);
        final AtomicInteger sizeCalls = new AtomicInteger();
        final Repository interleaved = new RepositoryWrapper(backendRepository) {
          @Override
          public RepositoryConnection getConnection() {
            return new RepositoryConnectionWrapper(this, super.getConnection()) {
              @Override
              public long size(final Resource... contexts) {
                final long result = super.size(contexts);
                if (sizeCalls.incrementAndGet() == 1) {
                  beforeSampleTaken.countDown();
                  awaitUninterruptibly(concurrentWriteCommitted);
                }
                return result;
              }
            };
          }
        };
        final GraphStoreRdf4j interleavedStore = new GraphStoreRdf4j(interleaved);
        final AtomicLong delta = new AtomicLong();

        // when
        final Thread adder = new Thread(() -> delta.set(interleavedStore.add(GRAPH_1, singleTripleGraph())));
        adder.start();
        beforeSampleTaken.await();
        backendStore.add(GRAPH_1, valueTriple("concurrent"));
        concurrentWriteCommitted.countDown();
        adder.join();

        // then — the delta reported by add() must be exactly the one triple it itself inserted; the
        // concurrently committed unrelated triple must not leak into it. Under a bare begin() (the
        // backend's default SNAPSHOT_READ) the two size() samples are two independent reads of the
        // then-current committed state, so the concurrent commit above leaks into the "after" sample
        // and this delta comes back as 2.
        assertThat(delta.get()).isEqualTo(1L);
        assertThat(backendStore.count(GRAPH_1)).isEqualTo(2L);
      } finally {
        backendRepository.shutDown();
      }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Backend.class)
    @DisplayName("overlapping transactions racing an ASK-guarded write, guard IRIs already known to the store"
        + " — the loser's commit fails, only one write wins")
    void inTransaction_overlappingAskGuardedWrites_whenGuardIrisKnownToStore_loserCommitFails(final Backend backend,
        @TempDir final Path tempDir) throws InterruptedException {
      // given — both transactions check "does this resource already have a value?" before writing.
      // A barrier makes both ASKs happen before either write (the ASK-guard-defeat scenario from
      // issue #17); a latch then forces the second transaction's commit to happen strictly after the
      // first's, so the outcome — who wins the race — is deterministic instead of flaky.
      //
      // The seed is load-bearing, not scenery: it puts GRAPH_1, SUBJECT and PREDICATE into the store
      // *before* the race, without satisfying the guard. A SERIALIZABLE guard read over IRIs the
      // store has never seen does not reliably register an observation, so the conflict below goes
      // undetected in a timing-dependent 6–12% of runs — see the "Limits" section on
      // DatasetTransactorRdf4j and issue #23. Without the seed this test is flaky because the
      // guarantee itself is. Per #52, that unseeded rate has since been re-measured against
      // NativeStore too (0 of 7000 runs missed the conflict — see DatasetTransactorRdf4j's
      // "Reconciled against NativeStore" Limits paragraph); this seeded case here is the
      // deterministic side of the guarantee, which the parameterization confirms holds on
      // NativeStore as well as on MemoryStore.
      final Repository backendRepository = backend.create(tempDir);
      backendRepository.init();
      try {
        final GraphStoreRdf4j backendStore = new GraphStoreRdf4j(backendRepository);
        final DatasetTransactorRdf4j backendTransactor = new DatasetTransactorRdf4j(backendRepository);
        backendStore.add(GRAPH_1, seedTriples());
        final String askGuard = "ASK { GRAPH <" + GRAPH_1.getIRIString() + "> { <" + SUBJECT.getIRIString() + "> <"
            + PREDICATE.getIRIString() + "> ?o } }";
        final CyclicBarrier bothGuardsChecked = new CyclicBarrier(2);
        final CountDownLatch firstCommitted = new CountDownLatch(1);
        final AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        final Thread winner = new Thread(() -> {
          backendTransactor.inTransaction(tx -> {
            tx.ask(askGuard);
            awaitUninterruptibly(bothGuardsChecked);
            tx.add(GRAPH_1, valueTriple("value-1"));
            return null;
          });
          firstCommitted.countDown();
        });
        final Thread loser = new Thread(() -> {
          try {
            backendTransactor.inTransaction(tx -> {
              final boolean alreadyPresent = tx.ask(askGuard);
              awaitUninterruptibly(bothGuardsChecked);
              awaitUninterruptibly(firstCommitted);
              if (!alreadyPresent) {
                tx.add(GRAPH_1, valueTriple("value-2"));
              }
              return null;
            });
          } catch (RuntimeException e) {
            secondFailure.set(e);
          }
        });

        // when
        winner.start();
        loser.start();
        winner.join();
        loser.join();

        // then — the loser's commit is rejected as a conflict, the store holds the two seed triples
        // plus exactly one of the two racing writes. The failure reaches the caller as the port's
        // neutral ConcurrencyConflictException, with the backend's signal kept as cause.
        assertThat(secondFailure.get()).isInstanceOf(ConcurrencyConflictException.class)
            .hasCauseInstanceOf(RepositoryException.class)
            .hasRootCauseInstanceOf(SailConflictException.class);
        assertThat(backendStore.count(GRAPH_1)).isEqualTo(3L);
      } finally {
        backendRepository.shutDown();
      }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Backend.class)
    @DisplayName("overlapping transactions racing a contains-guarded first insert, guard IRIs unknown"
        + " to the store — the loser's commit fails, only one write wins")
    void inTransaction_overlappingContainsGuardedWrites_whenGuardIrisUnknownToStore_loserCommitFails(
        final Backend backend, @TempDir final Path tempDir) throws InterruptedException {
      // given — the same race as the ASK test above, minus the seed: nothing in the store mentions
      // GRAPH_1, SUBJECT or PREDICATE yet, so this is the first-insert uniqueness case of issue #23.
      // The guard reads through contains() rather than SPARQL, which registers the observation the
      // SPARQL path fails to register — so the conflict is detected here where the ASK variant
      // misses it in a timing-dependent share of runs.
      final Repository backendRepository = backend.create(tempDir);
      backendRepository.init();
      try {
        final GraphStoreRdf4j backendStore = new GraphStoreRdf4j(backendRepository);
        final DatasetTransactorRdf4j backendTransactor = new DatasetTransactorRdf4j(backendRepository);
        final CyclicBarrier bothGuardsChecked = new CyclicBarrier(2);
        final CountDownLatch firstCommitted = new CountDownLatch(1);
        final AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        final Thread winner = new Thread(() -> {
          backendTransactor.inTransaction(tx -> {
            tx.contains(GRAPH_1, SUBJECT, PREDICATE, null);
            awaitUninterruptibly(bothGuardsChecked);
            tx.add(GRAPH_1, valueTriple("value-1"));
            return null;
          });
          firstCommitted.countDown();
        });
        final Thread loser = new Thread(() -> {
          try {
            backendTransactor.inTransaction(tx -> {
              final boolean alreadyPresent = tx.contains(GRAPH_1, SUBJECT, PREDICATE, null);
              awaitUninterruptibly(bothGuardsChecked);
              awaitUninterruptibly(firstCommitted);
              if (!alreadyPresent) {
                tx.add(GRAPH_1, valueTriple("value-2"));
              }
              return null;
            });
          } catch (RuntimeException e) {
            secondFailure.set(e);
          }
        });

        // when
        winner.start();
        loser.start();
        winner.join();
        loser.join();

        // then — the loser's commit is rejected as a conflict, exactly one of the two writes landed
        assertThat(secondFailure.get()).isInstanceOf(ConcurrencyConflictException.class)
            .hasCauseInstanceOf(RepositoryException.class)
            .hasRootCauseInstanceOf(SailConflictException.class);
        assertThat(backendStore.count(GRAPH_1)).isEqualTo(1L);
      } finally {
        backendRepository.shutDown();
      }
    }
  }
}
