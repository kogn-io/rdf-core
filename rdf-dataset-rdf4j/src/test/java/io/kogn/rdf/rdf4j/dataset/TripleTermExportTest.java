// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.rdf4j.RDF4JFactory;
import io.kogn.rdf.terms.IRI;

/**
 * A SPARQL update can store an RDF 1.2 triple term, which the port data model cannot represent;
 * {@code export} rejects it right away instead of returning a graph that fails when iterated.
 */
class TripleTermExportTest {

  private static final String EX = "http://example.org/";
  private static final String UPDATE = "INSERT DATA { GRAPH <" + EX + "g> { <" + EX + "s> <" + EX + "p> <<( <" + EX
      + "a> <" + EX + "b> <" + EX + "c> )>> } }";

  private final IRI graph = new RDF4JFactory().createIRI(EX + "g");
  private Repository repository;

  @BeforeEach
  void setUp() {
    repository = new SailRepository(new MemoryStore());
    repository.init();
    new DatasetTransactorRdf4j(repository).inTransaction(tx -> {
      tx.update(UPDATE);
      return null;
    });
  }

  @AfterEach
  void tearDown() {
    repository.shutDown();
  }

  @Test
  void graphStoreExportRejectsATripleTerm() {
    final GraphStoreRdf4j store = new GraphStoreRdf4j(repository);

    assertThatThrownBy(() -> store.export(graph)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("triple term");
  }

  @Test
  void datasetTxExportRejectsATripleTerm() {
    final DatasetTransactorRdf4j transactor = new DatasetTransactorRdf4j(repository);

    assertThatThrownBy(() -> transactor.inTransaction(tx -> tx.export(graph))).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("triple term");
  }

  private static final String CONSTRUCT = "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH ?g { ?s ?p ?o } }";

  @Test
  void sparqlQueryConstructRejectsATripleTerm() {
    final SparqlQueryRdf4j query = new SparqlQueryRdf4j(repository);

    assertThatThrownBy(() -> query.construct(CONSTRUCT)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("triple term");
  }

  @Test
  void datasetTxConstructRejectsATripleTerm() {
    final DatasetTransactorRdf4j transactor = new DatasetTransactorRdf4j(repository);

    assertThatThrownBy(() -> transactor.inTransaction(tx -> tx.construct(CONSTRUCT)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("triple term");
  }
}
