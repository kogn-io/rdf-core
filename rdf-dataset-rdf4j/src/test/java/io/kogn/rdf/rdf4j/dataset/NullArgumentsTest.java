// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.base.RepositoryConnectionWrapper;
import org.eclipse.rdf4j.repository.base.RepositoryWrapper;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.RdfFormat;
import io.kogn.rdf.rdf4j.RDF4JFactory;
import io.kogn.rdf.rdf4j.RDF4JIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.RDFTerm;

/**
 * A {@code null} argument reaches the caller of a dataset port as a {@link NullPointerException},
 * raised before the backend is touched and never translated into a port exception; an empty or
 * blank SPARQL string is an {@link IllegalArgumentException}, also before the backend.
 */
class NullArgumentsTest {

  private static final io.kogn.rdf.terms.IRI GRAPH = RDF4JIRI.of("https://example.org/graph/1");
  private static final io.kogn.rdf.terms.IRI SUBJECT = RDF4JIRI.of("https://example.org/subject");
  private static final io.kogn.rdf.terms.IRI PREDICATE = RDF4JIRI.of("https://example.org/predicate");
  private static final String SELECT = "SELECT * WHERE { GRAPH ?g { ?s ?p ?o } }";
  private static final String ASK = "ASK { GRAPH ?g { ?s ?p ?o } }";
  private static final String CONSTRUCT = "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?g { ?s ?p ?o } }";
  private static final String UPDATE = "INSERT DATA { GRAPH <https://example.org/graph/1> { <urn:a> <urn:b> <urn:c> } }";

  private Repository repository;
  private AtomicInteger connectionsOpened;
  private AtomicInteger operationsPrepared;
  private Repository observed;

  @BeforeEach
  void setUp() {
    repository = new SailRepository(new MemoryStore());
    repository.init();
    connectionsOpened = new AtomicInteger();
    operationsPrepared = new AtomicInteger();
    observed = new RepositoryWrapper(repository) {
      @Override
      public RepositoryConnection getConnection() {
        connectionsOpened.incrementAndGet();
        return new RepositoryConnectionWrapper(this, super.getConnection()) {
          @Override
          public TupleQuery prepareTupleQuery(final QueryLanguage ql, final String query) {
            operationsPrepared.incrementAndGet();
            return super.prepareTupleQuery(ql, query);
          }

          @Override
          public BooleanQuery prepareBooleanQuery(final QueryLanguage ql, final String query) {
            operationsPrepared.incrementAndGet();
            return super.prepareBooleanQuery(ql, query);
          }

          @Override
          public GraphQuery prepareGraphQuery(final QueryLanguage ql, final String query) {
            operationsPrepared.incrementAndGet();
            return super.prepareGraphQuery(ql, query);
          }

          @Override
          public Update prepareUpdate(final QueryLanguage ql, final String update) {
            operationsPrepared.incrementAndGet();
            return super.prepareUpdate(ql, update);
          }
        };
      }
    };
  }

  @AfterEach
  void tearDown() {
    repository.shutDown();
  }

  private static Map<String, RDFTerm> nullValue() {
    final Map<String, RDFTerm> bindings = new HashMap<>();
    bindings.put("s", null);
    return bindings;
  }

  private static Map<String, RDFTerm> nullName() {
    final Map<String, RDFTerm> bindings = new HashMap<>();
    bindings.put(null, SUBJECT);
    return bindings;
  }

  /** Every SPARQL operation, standing alone or on a transaction, called with a null argument. */
  static Stream<Arguments> nullSparqlCalls() {
    return Stream.of(Arguments.of("select(null)", (Consumer<SparqlCalls>) c -> c.select(null, Map.of())),
        Arguments.of("select(sparql, null)", (Consumer<SparqlCalls>) c -> c.select(SELECT, null)),
        Arguments.of("select(sparql, {s=null})", (Consumer<SparqlCalls>) c -> c.select(SELECT, nullValue())),
        Arguments.of("select(sparql, {null=term})", (Consumer<SparqlCalls>) c -> c.select(SELECT, nullName())),
        Arguments.of("ask(null)", (Consumer<SparqlCalls>) c -> c.ask(null, Map.of())),
        Arguments.of("ask(sparql, null)", (Consumer<SparqlCalls>) c -> c.ask(ASK, null)),
        Arguments.of("ask(sparql, {s=null})", (Consumer<SparqlCalls>) c -> c.ask(ASK, nullValue())),
        Arguments.of("ask(sparql, {null=term})", (Consumer<SparqlCalls>) c -> c.ask(ASK, nullName())),
        Arguments.of("construct(null)", (Consumer<SparqlCalls>) c -> c.construct(null, Map.of())),
        Arguments.of("construct(sparql, null)", (Consumer<SparqlCalls>) c -> c.construct(CONSTRUCT, null)),
        Arguments.of("construct(sparql, {s=null})", (Consumer<SparqlCalls>) c -> c.construct(CONSTRUCT, nullValue())),
        Arguments.of("construct(sparql, {null=term})", (Consumer<SparqlCalls>) c -> c.construct(CONSTRUCT, nullName())),
        Arguments.of("update(null)", (Consumer<SparqlCalls>) c -> c.update(null, Map.of())),
        Arguments.of("update(sparql, null)", (Consumer<SparqlCalls>) c -> c.update(UPDATE, null)),
        Arguments.of("update(sparql, {s=null})", (Consumer<SparqlCalls>) c -> c.update(UPDATE, nullValue())),
        Arguments.of("update(sparql, {null=term})", (Consumer<SparqlCalls>) c -> c.update(UPDATE, nullName())));
  }

  /** The four SPARQL operations, so one table drives both the standalone ports and a transaction. */
  private interface SparqlCalls {
    void select(String sparql, Map<String, RDFTerm> bindings);

    void ask(String sparql, Map<String, RDFTerm> bindings);

    void construct(String sparql, Map<String, RDFTerm> bindings);

    void update(String sparql, Map<String, RDFTerm> bindings);
  }

  private SparqlCalls standalonePorts() {
    final SparqlQueryRdf4j query = new SparqlQueryRdf4j(observed);
    final SparqlUpdateRdf4j update = new SparqlUpdateRdf4j(observed);
    return new SparqlCalls() {
      @Override
      public void select(final String sparql, final Map<String, RDFTerm> bindings) {
        query.select(sparql, bindings).toList();
      }

      @Override
      public void ask(final String sparql, final Map<String, RDFTerm> bindings) {
        query.ask(sparql, bindings);
      }

      @Override
      public void construct(final String sparql, final Map<String, RDFTerm> bindings) {
        query.construct(sparql, bindings);
      }

      @Override
      public void update(final String sparql, final Map<String, RDFTerm> bindings) {
        update.update(sparql, bindings);
      }
    };
  }

  private static SparqlCalls transactional(final DatasetTx tx) {
    return new SparqlCalls() {
      @Override
      public void select(final String sparql, final Map<String, RDFTerm> bindings) {
        tx.select(sparql, bindings).toList();
      }

      @Override
      public void ask(final String sparql, final Map<String, RDFTerm> bindings) {
        tx.ask(sparql, bindings);
      }

      @Override
      public void construct(final String sparql, final Map<String, RDFTerm> bindings) {
        tx.construct(sparql, bindings);
      }

      @Override
      public void update(final String sparql, final Map<String, RDFTerm> bindings) {
        tx.update(sparql, bindings);
      }
    };
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nullSparqlCalls")
  @DisplayName("a null argument to a standalone SPARQL port is a NullPointerException, before a connection is opened")
  void standaloneSparql_nullArgument_isNullPointerException(final String call, final Consumer<SparqlCalls> operation) {
    final SparqlCalls ports = standalonePorts();

    assertThatThrownBy(() -> operation.accept(ports)).isExactlyInstanceOf(NullPointerException.class);
    assertThat(connectionsOpened).hasValue(0);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nullSparqlCalls")
  @DisplayName("a null argument to a SPARQL operation of a DatasetTx is a NullPointerException, before it is prepared")
  void transactionalSparql_nullArgument_isNullPointerException(final String call,
      final Consumer<SparqlCalls> operation) {
    final DatasetTransactorRdf4j transactor = new DatasetTransactorRdf4j(observed);

    assertThatThrownBy(() -> transactor.inTransaction(tx -> {
      operation.accept(transactional(tx));
      return null;
    })).isExactlyInstanceOf(NullPointerException.class);
    assertThat(operationsPrepared).hasValue(0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\n\t"})
  @DisplayName("an empty or blank SPARQL string is an IllegalArgumentException on every operation, before the backend")
  void sparql_emptyOrBlank_isIllegalArgumentException(final String sparql) {
    final SparqlCalls ports = standalonePorts();
    final DatasetTransactorRdf4j transactor = new DatasetTransactorRdf4j(observed);
    final List<Consumer<SparqlCalls>> operations = List.of(c -> c.select(sparql, Map.of()),
        c -> c.ask(sparql, Map.of()), c -> c.construct(sparql, Map.of()), c -> c.update(sparql, Map.of()));

    for (final Consumer<SparqlCalls> operation : operations) {
      assertThatThrownBy(() -> operation.accept(ports)).isExactlyInstanceOf(IllegalArgumentException.class);
    }
    assertThat(connectionsOpened).hasValue(0);

    for (final Consumer<SparqlCalls> operation : operations) {
      assertThatThrownBy(() -> transactor.inTransaction(tx -> {
        operation.accept(transactional(tx));
        return null;
      })).isExactlyInstanceOf(IllegalArgumentException.class);
    }
    assertThat(operationsPrepared).hasValue(0);
  }

  @Test
  @DisplayName("the one-argument SPARQL overloads report a null string as a NullPointerException")
  void sparqlOverloads_nullString_isNullPointerException() {
    final SparqlQueryRdf4j query = new SparqlQueryRdf4j(observed);
    final SparqlUpdateRdf4j update = new SparqlUpdateRdf4j(observed);

    assertThatThrownBy(() -> query.select(null)).isExactlyInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> query.ask(null)).isExactlyInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> query.construct(null)).isExactlyInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> update.update(null)).isExactlyInstanceOf(NullPointerException.class);
    assertThat(connectionsOpened).hasValue(0);
  }

  @Test
  @DisplayName("a null argument to a GraphStore operation is a NullPointerException, before a connection is opened")
  void graphStore_nullArgument_isNullPointerException() {
    final GraphStoreRdf4j store = new GraphStoreRdf4j(observed);
    final Graph triples = new RDF4JFactory().createGraph();

    for (final Consumer<GraphStoreRdf4j> operation : List.<Consumer<GraphStoreRdf4j>>of(s -> s.add(null, triples),
        s -> s.add(GRAPH, null), s -> s.remove(null, triples), s -> s.remove(GRAPH, null), s -> s.clear(null),
        s -> s.snapshot(null), s -> s.count(null))) {
      assertThatThrownBy(() -> operation.accept(store)).isExactlyInstanceOf(NullPointerException.class);
    }
    assertThat(connectionsOpened).hasValue(0);
  }

  @Test
  @DisplayName("a null named graph or a null graph on a DatasetTx is a NullPointerException")
  void datasetTx_nullArgument_isNullPointerException() {
    final DatasetTransactorRdf4j transactor = new DatasetTransactorRdf4j(observed);
    final Graph triples = new RDF4JFactory().createGraph();

    for (final Consumer<DatasetTx> operation : List.<Consumer<DatasetTx>>of(tx -> tx.add(null, triples),
        tx -> tx.add(GRAPH, null), tx -> tx.remove(null, triples), tx -> tx.remove(GRAPH, null), tx -> tx.clear(null),
        tx -> tx.snapshot(null), tx -> tx.count(null), tx -> tx.contains(null, SUBJECT, PREDICATE, null),
        tx -> tx.match(null, SUBJECT, null, null))) {
      assertThatThrownBy(() -> transactor.inTransaction(tx -> {
        operation.accept(tx);
        return null;
      })).isExactlyInstanceOf(NullPointerException.class);
    }
  }

  @Test
  @DisplayName("a null unit of work is a NullPointerException, before a connection is opened")
  void transactor_nullWork_isNullPointerException() {
    final DatasetTransactorRdf4j transactor = new DatasetTransactorRdf4j(observed);

    assertThatThrownBy(() -> transactor.inTransaction(null)).isExactlyInstanceOf(NullPointerException.class);
    assertThat(connectionsOpened).hasValue(0);
  }

  @Test
  @DisplayName("a null argument to an export is a NullPointerException, before a connection is opened")
  void export_nullArgument_isNullPointerException() {
    final DatasetExportRdf4j export = new DatasetExportRdf4j(observed);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    assertThatThrownBy(() -> export.export(null, RdfFormat.NQUADS)).isExactlyInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> export.export(out, null)).isExactlyInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> export.export(null, RdfFormat.TURTLE, GRAPH))
        .isExactlyInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> export.export(out, null, GRAPH)).isExactlyInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> export.export(out, RdfFormat.TURTLE, null))
        .isExactlyInstanceOf(NullPointerException.class);
    assertThat(connectionsOpened).hasValue(0);
  }

  @Test
  @DisplayName("a null argument to an import is a NullPointerException, before the stream is read")
  void graphImport_nullArgument_isNullPointerException() {
    final GraphImportRdf4j graphImport = new GraphImportRdf4j();
    final ByteArrayInputStream in = new ByteArrayInputStream(new byte[] {'!'});

    assertThatThrownBy(() -> graphImport.readTurtle(null, GRAPH)).isExactlyInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> graphImport.readTurtle(in, null)).isExactlyInstanceOf(NullPointerException.class);
    assertThat(in.available()).isEqualTo(1);
  }

  @Test
  @DisplayName("every content adapter rejects a null repository on construction")
  void constructors_nullRepository_isNullPointerException() {
    assertThatThrownBy(() -> new GraphStoreRdf4j(null)).isExactlyInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SparqlQueryRdf4j(null)).isExactlyInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SparqlUpdateRdf4j(null)).isExactlyInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DatasetTransactorRdf4j(null)).isExactlyInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DatasetExportRdf4j(null)).isExactlyInstanceOf(NullPointerException.class);
  }
}
