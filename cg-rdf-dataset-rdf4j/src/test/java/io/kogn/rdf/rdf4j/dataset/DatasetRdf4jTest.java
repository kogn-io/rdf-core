// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.BindingSet;
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

    @BeforeEach
    void setUp() {
      sparqlUpdate = new SparqlUpdateRdf4j(repository);
    }

    @Test
    @DisplayName("INSERT DATA makes triple visible via ask")
    void update_insertData_tripleIsVisible() {
      // given
      final String insert = "INSERT DATA { GRAPH <" + GRAPH_1.getIRIString() + "> { <" + SUBJECT.getIRIString() + "> <"
          + PREDICATE.getIRIString() + "> <" + OBJECT.getIRIString() + "> } }";
      final String ask = "ASK { GRAPH <" + GRAPH_1.getIRIString() + "> { <" + SUBJECT.getIRIString() + "> <"
          + PREDICATE.getIRIString() + "> <" + OBJECT.getIRIString() + "> } }";

      // when
      sparqlUpdate.update(insert);

      // then
      assertThat(sparqlUpdate.ask(ask)).isTrue();
    }

    @Test
    @DisplayName("ask returns false when the pattern has no match")
    void ask_noMatch_returnsFalse() {
      // when / then
      assertThat(sparqlUpdate.ask("ASK { GRAPH <" + GRAPH_1.getIRIString() + "> { ?s ?p ?o } }")).isFalse();
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
      assertThat(sparqlUpdate.ask("ASK { GRAPH <" + graphIri + "> { ?s ?p ?o } }")).isFalse();
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
  }
}
