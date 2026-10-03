// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.rdf4j.RDF4JBindingSet;
import io.kogn.rdf.rdf4j.RDF4JGraph;
import io.kogn.rdf.terms.RDFTerm;
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
    return select(sparql, Map.of());
  }

  @Override
  public Stream<BindingSet> select(final String sparql, final Map<String, RDFTerm> bindings) {
    try (RepositoryConnection conn = repository.getConnection()) {
      final TupleQuery query = SparqlErrors
          .bound(SparqlErrors.translating(() -> conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql)), bindings);
      final List<org.eclipse.rdf4j.query.BindingSet> rows = SparqlErrors
          .translating(() -> QueryResults.asList(query.evaluate()));
      return rows.stream().<BindingSet>map(RDF4JBindingSet::new).toList().stream();
    }
  }

  @Override
  public ReadableGraph construct(final String sparql) {
    return construct(sparql, Map.of());
  }

  @Override
  public ReadableGraph construct(final String sparql, final Map<String, RDFTerm> bindings) {
    try (RepositoryConnection conn = repository.getConnection()) {
      final GraphQuery query = SparqlErrors
          .bound(SparqlErrors.translating(() -> conn.prepareGraphQuery(QueryLanguage.SPARQL, sparql)), bindings);
      final Model model = SparqlErrors.translating(() -> QueryResults.asModel(query.evaluate()));
      return new RDF4JGraph(model);
    }
  }

  @Override
  public boolean ask(final String sparql) {
    return ask(sparql, Map.of());
  }

  @Override
  public boolean ask(final String sparql, final Map<String, RDFTerm> bindings) {
    try (RepositoryConnection conn = repository.getConnection()) {
      final BooleanQuery query = SparqlErrors
          .bound(SparqlErrors.translating(() -> conn.prepareBooleanQuery(QueryLanguage.SPARQL, sparql)), bindings);
      return SparqlErrors.translating(query::evaluate);
    }
  }
}
