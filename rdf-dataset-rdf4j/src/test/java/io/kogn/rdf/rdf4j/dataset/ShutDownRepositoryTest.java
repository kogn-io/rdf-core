// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.function.Function;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.kogn.rdf.dataset.DatasetStorageException;
import io.kogn.rdf.dataset.RdfExportException;
import io.kogn.rdf.dataset.RdfFormat;
import io.kogn.rdf.dataset.SparqlEvaluationException;
import io.kogn.rdf.rdf4j.RDF4JFactory;
import io.kogn.rdf.rdf4j.RDF4JIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;

/**
 * Issue #244: a content adapter used after its repository was shut down fails, instead of
 * letting RDF4J's {@code getConnection()} initialize the repository again — which brings a
 * {@code MemoryStore} back empty and makes a {@code NativeStore} lock its directory again.
 */
class ShutDownRepositoryTest {

  private static final IRI GRAPH = RDF4JIRI.of("https://example.org/graph/1");
  private static final IRI SUBJECT = RDF4JIRI.of("https://example.org/subject");
  private static final IRI PREDICATE = RDF4JIRI.of("https://example.org/predicate");
  private static final IRI OBJECT = RDF4JIRI.of("https://example.org/object");
  private static final String ASK_ANY = "ASK { ?s ?p ?o }";
  private static final String INSERT_TRIPLE = "INSERT DATA { <" + SUBJECT.getIRIString() + "> <"
      + PREDICATE.getIRIString() + "> <" + OBJECT.getIRIString() + "> }";

  @TempDir
  Path tmp;

  private Repository repository;

  @AfterEach
  void tearDown() {
    if (repository != null && repository.isInitialized()) {
      repository.shutDown();
    }
  }

  /** The two sails the hosting adapter ships. */
  enum Backend {
    MEMORY(dir -> new SailRepository(new MemoryStore())), NATIVE(
        dir -> new SailRepository(new NativeStore(dir.toFile())));

    private final Function<Path, Repository> factory;

    Backend(final Function<Path, Repository> factory) {
      this.factory = factory;
    }
  }

  /** An initialized repository holding one triple in {@link #GRAPH}, shut down again. */
  private Repository shutDownAfterOneWrite(final Backend backend) {
    repository = backend.factory.apply(tmp);
    repository.init();
    new GraphStoreRdf4j(repository).add(GRAPH, oneTriple());
    repository.shutDown();
    return repository;
  }

  private Graph oneTriple() {
    final RDF4JFactory rdf = new RDF4JFactory();
    final Graph graph = rdf.createGraph();
    graph.add(rdf.createTriple(SUBJECT, PREDICATE, OBJECT));
    return graph;
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  @DisplayName("GraphStore.count after shutDown fails and leaves the repository shut down")
  void graphStoreCount_afterShutDown_fails(final Backend backend) {
    final GraphStoreRdf4j store = new GraphStoreRdf4j(shutDownAfterOneWrite(backend));

    assertThatThrownBy(store::count).isInstanceOf(DatasetStorageException.class);
    assertThat(repository.isInitialized()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  @DisplayName("GraphStore.add after shutDown fails and leaves the repository shut down")
  void graphStoreAdd_afterShutDown_fails(final Backend backend) {
    final GraphStoreRdf4j store = new GraphStoreRdf4j(shutDownAfterOneWrite(backend));

    assertThatThrownBy(() -> store.add(GRAPH, oneTriple())).isInstanceOf(DatasetStorageException.class);
    assertThat(repository.isInitialized()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  @DisplayName("DatasetTransactor.inTransaction after shutDown fails and leaves the repository shut down")
  void transactor_afterShutDown_fails(final Backend backend) {
    final DatasetTransactorRdf4j transactor = new DatasetTransactorRdf4j(shutDownAfterOneWrite(backend));

    assertThatThrownBy(() -> transactor.inTransaction(tx -> tx.count())).isInstanceOf(DatasetStorageException.class);
    assertThat(repository.isInitialized()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  @DisplayName("SparqlQuery.ask after shutDown fails as an evaluation failure and leaves the repository shut down")
  void sparqlQuery_afterShutDown_fails(final Backend backend) {
    final SparqlQueryRdf4j query = new SparqlQueryRdf4j(shutDownAfterOneWrite(backend));

    assertThatThrownBy(() -> query.ask(ASK_ANY)).isInstanceOf(SparqlEvaluationException.class)
        .hasCauseInstanceOf(org.eclipse.rdf4j.repository.RepositoryException.class);
    assertThat(repository.isInitialized()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  @DisplayName("SparqlUpdate.update after shutDown fails as an evaluation failure and leaves the repository shut down")
  void sparqlUpdate_afterShutDown_fails(final Backend backend) {
    final SparqlUpdateRdf4j update = new SparqlUpdateRdf4j(shutDownAfterOneWrite(backend));

    assertThatThrownBy(() -> update.update(INSERT_TRIPLE)).isInstanceOf(SparqlEvaluationException.class);
    assertThat(repository.isInitialized()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  @DisplayName("DatasetExport.export after shutDown fails and leaves the repository shut down")
  void datasetExport_afterShutDown_fails(final Backend backend) {
    final DatasetExportRdf4j export = new DatasetExportRdf4j(shutDownAfterOneWrite(backend));

    assertThatThrownBy(() -> export.export(new ByteArrayOutputStream(), RdfFormat.NQUADS))
        .isInstanceOf(RdfExportException.class);
    assertThat(repository.isInitialized()).isFalse();
  }

  @Test
  @DisplayName("a repository that was never initialized is not initialized lazily either")
  void neverInitialized_fails() {
    repository = new SailRepository(new MemoryStore());
    final GraphStoreRdf4j store = new GraphStoreRdf4j(repository);

    assertThatThrownBy(store::count).isInstanceOf(DatasetStorageException.class)
        .hasMessageContaining("not initialized");
    assertThat(repository.isInitialized()).isFalse();
  }
}
