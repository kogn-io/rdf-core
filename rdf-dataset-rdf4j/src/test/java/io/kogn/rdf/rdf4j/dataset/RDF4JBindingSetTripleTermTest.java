// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

/**
 * A result row binding an RDF 1.2 triple term, which a SPARQL update can store and the port data
 * model cannot represent, is rejected when the binding set is created, with a message that names
 * the cause, instead of failing later on first access.
 */
class RDF4JBindingSetTripleTermTest {

  private static final String EX = "http://example.org/";

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
