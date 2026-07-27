// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.rdf4j.RDF4JBindingSet;
import io.kogn.rdf.rdf4j.RDF4JGraph;
import io.kogn.rdf.rdf4j.internal.RDF4JConverters;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.Triple;

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
 *
 * <p>{@link #contains(IRI, io.kogn.rdf.terms.BlankNodeOrIRI, IRI, io.kogn.rdf.terms.RDFTerm)}
 * maps to {@link RepositoryConnection#hasStatement} rather than to a SPARQL {@code ASK}, which
 * is what makes it usable as a conflict-protected guard — see the "Limits" section on
 * {@link DatasetTransactorRdf4j}. Inferred statements are excluded, matching
 * {@link GraphStoreRdf4j}.</p>
 *
 * <p>{@link #add} and {@link #remove} compute their delta per triple, via
 * {@link RepositoryConnection#hasStatement} before mutating each one, instead of sampling
 * {@link RepositoryConnection#size} before and after the whole call the way
 * {@link GraphStoreRdf4j} does. Under the {@code SERIALIZABLE} isolation this transaction runs
 * at (see {@link DatasetTransactorRdf4j}), a wildcard {@code size(context)} read observes the
 * <em>entire</em> named graph, so it conflicts with a concurrent commit anywhere in that graph —
 * including one that touched none of the triples this call added or removed. A concrete
 * {@code hasStatement(s, p, o, false, context)} lookup per triple observes only that one
 * pattern, so the conflict surface of {@code add}/{@code remove} is the triples they actually
 * touch. See <a href="https://github.com/kogn-io/rdf-core/issues/64">issue 64</a> and
 * ADR-0012.</p>
 *
 * <p>{@link #count(IRI)}, {@link #count()} and {@link #export} are unaffected: they still read
 * the whole named graph, and that whole-graph observation is deliberate rather than a gap
 * — a transaction that already asked "how many/which triples are in this graph" is meant to
 * conflict with any concurrent writer to that graph, the same way a {@code contains} guard
 * (ADR-0008) is meant to conflict with a writer of the exact pattern it read. See ADR-0012.</p>
 */
class DatasetTxRdf4j implements DatasetTx {

  private final RepositoryConnection connection;

  DatasetTxRdf4j(final RepositoryConnection connection) {
    this.connection = connection;
  }

  @Override
  public long add(final IRI namedGraph, final ReadableGraph triples) {
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    long added = 0;
    for (final Triple triple : triples.stream().toList()) {
      final org.eclipse.rdf4j.model.Resource subject = RDF4JConverters.toRDF4JResource(triple.getSubject());
      final org.eclipse.rdf4j.model.IRI predicate = RDF4JConverters.toRDF4JIRI(triple.getPredicate());
      final org.eclipse.rdf4j.model.Value object = RDF4JConverters.toRDF4JValue(triple.getObject());
      if (connection.hasStatement(subject, predicate, object, false, context)) {
        continue;
      }
      connection.add(subject, predicate, object, context);
      added++;
    }
    return added;
  }

  @Override
  public long remove(final IRI namedGraph, final ReadableGraph triples) {
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    long removed = 0;
    for (final Triple triple : triples.stream().toList()) {
      final org.eclipse.rdf4j.model.Resource subject = RDF4JConverters.toRDF4JResource(triple.getSubject());
      final org.eclipse.rdf4j.model.IRI predicate = RDF4JConverters.toRDF4JIRI(triple.getPredicate());
      final org.eclipse.rdf4j.model.Value object = RDF4JConverters.toRDF4JValue(triple.getObject());
      if (!connection.hasStatement(subject, predicate, object, false, context)) {
        continue;
      }
      connection.remove(subject, predicate, object, context);
      removed++;
    }
    return removed;
  }

  @Override
  public void clear(final IRI namedGraph) {
    connection.clear(RDF4JConverters.toRDF4JIRI(namedGraph));
  }

  @Override
  public ReadableGraph export(final IRI namedGraph) {
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    final Model model = new LinkedHashModel();
    try (RepositoryResult<Statement> result = connection.getStatements(null, null, null, false, context)) {
      result.forEach(model::add);
    }
    return new RDF4JGraph(model);
  }

  @Override
  public long count(final IRI namedGraph) {
    return connection.size(RDF4JConverters.toRDF4JIRI(namedGraph));
  }

  @Override
  public long count() {
    return connection.size();
  }

  @Override
  public void update(final String sparql) {
    update(sparql, Map.of());
  }

  @Override
  public void update(final String sparql, final Map<String, RDFTerm> bindings) {
    SparqlErrors.bound(SparqlErrors.preparing(() -> connection.prepareUpdate(QueryLanguage.SPARQL, sparql)), bindings)
        .execute();
  }

  @Override
  public Stream<BindingSet> select(final String sparql) {
    return select(sparql, Map.of());
  }

  @Override
  public Stream<BindingSet> select(final String sparql, final Map<String, RDFTerm> bindings) {
    final TupleQuery query = SparqlErrors
        .bound(SparqlErrors.preparing(() -> connection.prepareTupleQuery(QueryLanguage.SPARQL, sparql)), bindings);
    final List<BindingSet> results = new ArrayList<>();
    try (TupleQueryResult result = query.evaluate()) {
      while (result.hasNext()) {
        results.add(new RDF4JBindingSet(result.next()));
      }
    }
    return results.stream();
  }

  @Override
  public boolean contains(final IRI namedGraph, final BlankNodeOrIRI subject, final IRI predicate,
      final RDFTerm object) {
    return connection.hasStatement(subject == null ? null : RDF4JConverters.toRDF4JResource(subject),
        predicate == null ? null : RDF4JConverters.toRDF4JIRI(predicate),
        object == null ? null : RDF4JConverters.toRDF4JValue(object), false, RDF4JConverters.toRDF4JIRI(namedGraph));
  }

  @Override
  public boolean ask(final String sparql) {
    return ask(sparql, Map.of());
  }

  @Override
  public boolean ask(final String sparql, final Map<String, RDFTerm> bindings) {
    return SparqlErrors
        .bound(SparqlErrors.preparing(() -> connection.prepareBooleanQuery(QueryLanguage.SPARQL, sparql)), bindings)
        .evaluate();
  }

  @Override
  public ReadableGraph construct(final String sparql) {
    return construct(sparql, Map.of());
  }

  @Override
  public ReadableGraph construct(final String sparql, final Map<String, RDFTerm> bindings) {
    final GraphQuery query = SparqlErrors
        .bound(SparqlErrors.preparing(() -> connection.prepareGraphQuery(QueryLanguage.SPARQL, sparql)), bindings);
    final Model model = QueryResults.asModel(query.evaluate());
    return new RDF4JGraph(model);
  }
}
