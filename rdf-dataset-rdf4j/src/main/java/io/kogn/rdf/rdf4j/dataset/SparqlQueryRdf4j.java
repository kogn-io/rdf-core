// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.rdf4j.RDF4JBindingSet;
import io.kogn.rdf.rdf4j.RDF4JGraph;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * RDF4J-based implementation of {@link SparqlQuery}.
 *
 * <p>Each operation opens a dedicated {@link RepositoryConnection} and closes it
 * immediately after execution, following the same pattern as
 * {@link SparqlUpdateRdf4j}. Results are materialised before the connection is
 * closed so that the caller never holds open store resources.</p>
 */
public class SparqlQueryRdf4j implements SparqlQuery {

  private final Repository repository;

  /**
   * Creates a SPARQL query port backed by the given RDF4J repository.
   *
   * @param repository the repository to open connections against
   */
  public SparqlQueryRdf4j(final Repository repository) {
    this.repository = repository;
  }

  @Override
  public Stream<BindingSet> select(final String sparql) {
    try (RepositoryConnection conn = repository.getConnection()) {
      final List<BindingSet> results = new ArrayList<>();
      try (TupleQueryResult result = SparqlErrors.preparing(() -> conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql))
          .evaluate()) {
        while (result.hasNext()) {
          results.add(new RDF4JBindingSet(result.next()));
        }
      }
      return results.stream();
    }
  }

  @Override
  public ReadableGraph construct(final String sparql) {
    try (RepositoryConnection conn = repository.getConnection()) {
      final Model model = QueryResults
          .asModel(SparqlErrors.preparing(() -> conn.prepareGraphQuery(QueryLanguage.SPARQL, sparql)).evaluate());
      return new RDF4JGraph(model);
    }
  }

  @Override
  public boolean ask(final String sparql) {
    try (RepositoryConnection conn = repository.getConnection()) {
      return SparqlErrors.preparing(() -> conn.prepareBooleanQuery(QueryLanguage.SPARQL, sparql)).evaluate();
    }
  }
}
