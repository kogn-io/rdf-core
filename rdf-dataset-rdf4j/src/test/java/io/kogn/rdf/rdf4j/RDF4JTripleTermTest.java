// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

/**
 * An RDF 1.2 triple term, which a SPARQL update can store, has no counterpart in the port data
 * model; the wrappers reject it when they are created, with a message that names the cause, instead
 * of failing later on first access.
 */
class RDF4JTripleTermTest {

  private static final String EX = "http://example.org/";

  private static Value tripleTerm() {
    return Values.tripleTerm(Values.iri(EX + "a"), Values.iri(EX + "b"), Values.iri(EX + "c"));
  }

  @Test
  void tripleWithATripleTermObjectIsRejectedOnCreation() {
    assertThatThrownBy(() -> new RDF4JTriple(Values.iri(EX + "s"), Values.iri(EX + "p"), tripleTerm()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("triple term");
  }

  @Test
  void bindingSetWithATripleTermValueIsRejectedOnCreation() {
    final SailRepository repository = new SailRepository(new MemoryStore());
    try (RepositoryConnection connection = repository.getConnection()) {
      connection
          .prepareUpdate(QueryLanguage.SPARQL,
              "INSERT DATA { <" + EX + "s> <" + EX + "p> <<( <" + EX + "a> <" + EX + "b> <" + EX + "c> )>> }")
          .execute();
      try (TupleQueryResult result = connection.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT ?o WHERE { ?s ?p ?o }")
          .evaluate()) {
        final BindingSet row = result.next();

        assertThat(row.getValue("o")).isInstanceOf(org.eclipse.rdf4j.model.TripleTerm.class);
        assertThatThrownBy(() -> new RDF4JBindingSet(row)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("triple term");
      }
    } finally {
      repository.shutDown();
    }
  }

  @Test
  void bindingSetWithoutTripleTermsStillWorks() {
    final SailRepository repository = new SailRepository(new MemoryStore());
    try (RepositoryConnection connection = repository.getConnection()) {
      try (TupleQueryResult result = connection
          .prepareTupleQuery(QueryLanguage.SPARQL, "SELECT ?x WHERE { BIND(1 AS ?x) }")
          .evaluate()) {
        assertThat(new RDF4JBindingSet(result.next()).hasBinding("x")).isTrue();
      }
    } finally {
      repository.shutDown();
    }
  }
}
