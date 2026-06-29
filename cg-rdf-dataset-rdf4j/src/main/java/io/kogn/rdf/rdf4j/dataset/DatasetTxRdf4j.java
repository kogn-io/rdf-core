// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.rdf4j.RDF4JBindingSet;
import io.kogn.rdf.rdf4j.RDF4JConverters;
import io.kogn.rdf.rdf4j.RDF4JGraph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * RDF4J-based implementation of {@link DatasetTx}.
 *
 * <p>Package-private: only created by {@link DatasetTransactorRdf4j} during a
 * transaction. All operations delegate to the shared {@link RepositoryConnection}
 * that is managed by the transactor — no new connection is opened here, ensuring
 * read-your-writes semantics within a single unit-of-work.</p>
 *
 * <p>{@link #select(String)} collects results eagerly so that the
 * {@link TupleQueryResult} is closed before returning, preventing resource leaks
 * across transaction boundaries.</p>
 */
class DatasetTxRdf4j implements DatasetTx {

  private final RepositoryConnection connection;

  DatasetTxRdf4j(final RepositoryConnection connection) {
    this.connection = connection;
  }

  @Override
  public void add(final IRI namedGraph, final ReadableGraph triples) {
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    triples.stream()
        .forEach(triple -> connection.add(RDF4JConverters.toRDF4JResource(triple.getSubject()),
            RDF4JConverters.toRDF4JIRI(triple.getPredicate()), RDF4JConverters.toRDF4JValue(triple.getObject()),
            context));
  }

  @Override
  public void remove(final IRI namedGraph, final ReadableGraph triples) {
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    triples.stream()
        .forEach(triple -> connection.remove(RDF4JConverters.toRDF4JResource(triple.getSubject()),
            RDF4JConverters.toRDF4JIRI(triple.getPredicate()), RDF4JConverters.toRDF4JValue(triple.getObject()),
            context));
  }

  @Override
  public void clear(final IRI namedGraph) {
    connection.clear(RDF4JConverters.toRDF4JIRI(namedGraph));
  }

  @Override
  public void update(final String sparql) {
    connection.prepareUpdate(QueryLanguage.SPARQL, sparql).execute();
  }

  @Override
  public Stream<BindingSet> select(final String sparql) {
    final TupleQuery query = connection.prepareTupleQuery(QueryLanguage.SPARQL, sparql);
    final List<BindingSet> results = new ArrayList<>();
    try (TupleQueryResult result = query.evaluate()) {
      while (result.hasNext()) {
        results.add(new RDF4JBindingSet(result.next()));
      }
    }
    return results.stream();
  }

  @Override
  public boolean ask(final String sparql) {
    return connection.prepareBooleanQuery(QueryLanguage.SPARQL, sparql).evaluate();
  }

  @Override
  public ReadableGraph construct(final String sparql) {
    final GraphQuery query = connection.prepareGraphQuery(QueryLanguage.SPARQL, sparql);
    final Model model = QueryResults.asModel(query.evaluate());
    return new RDF4JGraph(model);
  }
}
