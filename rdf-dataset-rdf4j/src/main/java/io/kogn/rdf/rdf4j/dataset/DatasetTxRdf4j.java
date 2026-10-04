// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetStorageException;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.rdf4j.RDF4JBindingSet;
import io.kogn.rdf.rdf4j.RDF4JGraph;
import io.kogn.rdf.rdf4j.internal.RDF4JConverters;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
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
 * {@code TupleQueryResult} is closed before returning, preventing resource leaks
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
 *
 * <p>The graph operations translate a backend failure into the neutral
 * {@link DatasetStorageException} (see {@link StorageErrors}), the SPARQL operations into
 * {@code MalformedSparqlException}/{@code SparqlEvaluationException} (see {@link SparqlErrors}).
 * Using an instance after its transaction ended is a programming error and is detected up
 * front, by every operation alike, as an {@link IllegalStateException} — never as one of those
 * two failure types, which a caller may treat as transient.</p>
 */
class DatasetTxRdf4j implements DatasetTx {

  private final RepositoryConnection connection;

  private volatile boolean active = true;

  DatasetTxRdf4j(final RepositoryConnection connection) {
    this.connection = connection;
  }

  /**
   * Ends this transaction object's validity; called by the transactor once the transaction is
   * over, before the connection is closed, whether it committed or rolled back.
   */
  void invalidate() {
    active = false;
  }

  private void requireActive() {
    if (!active) {
      throw new IllegalStateException("the transaction has ended; a DatasetTx must not be used outside the"
          + " DatasetTransactor#inTransaction call that created it");
    }
  }

  @Override
  public long add(final IRI namedGraph, final ReadableGraph triples) {
    requireActive();
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    final List<Statement> statements = RDF4JConverters.toStatements(triples);
    return StorageErrors.translating(() -> {
      long added = 0;
      for (final Statement statement : statements) {
        if (connection.hasStatement(statement.getSubject(), statement.getPredicate(), statement.getObject(), false,
            context)) {
          continue;
        }
        connection.add(statement.getSubject(), statement.getPredicate(), statement.getObject(), context);
        added++;
      }
      return added;
    });
  }

  @Override
  public long remove(final IRI namedGraph, final ReadableGraph triples) {
    requireActive();
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    final List<Statement> statements = RDF4JConverters.toStatements(triples);
    return StorageErrors.translating(() -> {
      long removed = 0;
      for (final Statement statement : statements) {
        if (!connection.hasStatement(statement.getSubject(), statement.getPredicate(), statement.getObject(), false,
            context)) {
          continue;
        }
        connection.remove(statement.getSubject(), statement.getPredicate(), statement.getObject(), context);
        removed++;
      }
      return removed;
    });
  }

  @Override
  public void clear(final IRI namedGraph) {
    requireActive();
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    StorageErrors.running(() -> connection.clear(context));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Rejects an RDF 1.2 triple term in the graph with an {@link IllegalStateException}: a SPARQL
   * update can store one, the port data model cannot represent it.</p>
   *
   * @throws IllegalStateException if the graph holds an RDF 1.2 triple term
   */
  @Override
  public ReadableGraph export(final IRI namedGraph) {
    requireActive();
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    final List<Statement> statements = StorageErrors.translating(() -> {
      try (RepositoryResult<Statement> result = connection.getStatements(null, null, null, false, context)) {
        return result.asList();
      }
    });
    final Model model = new LinkedHashModel(statements);
    RDF4JConverters.requireNoTripleTerms(model);
    return new RDF4JGraph(model);
  }

  @Override
  public long count(final IRI namedGraph) {
    requireActive();
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    return StorageErrors.translating(() -> connection.size(context));
  }

  @Override
  public long count() {
    requireActive();
    return StorageErrors.translating(connection::size);
  }

  @Override
  public void update(final String sparql) {
    update(sparql, Map.of());
  }

  @Override
  public void update(final String sparql, final Map<String, RDFTerm> bindings) {
    requireActive();
    final Update operation = SparqlErrors
        .bound(SparqlErrors.translating(() -> connection.prepareUpdate(QueryLanguage.SPARQL, sparql)), bindings);
    SparqlErrors.executing(operation::execute);
  }

  @Override
  public Stream<BindingSet> select(final String sparql) {
    return select(sparql, Map.of());
  }

  @Override
  public Stream<BindingSet> select(final String sparql, final Map<String, RDFTerm> bindings) {
    requireActive();
    final TupleQuery query = SparqlErrors
        .bound(SparqlErrors.translating(() -> connection.prepareTupleQuery(QueryLanguage.SPARQL, sparql)), bindings);
    final List<org.eclipse.rdf4j.query.BindingSet> rows = SparqlErrors
        .translating(() -> QueryResults.asList(query.evaluate()));
    return rows.stream().<BindingSet>map(RDF4JBindingSet::new).toList().stream();
  }

  @Override
  public boolean contains(final IRI namedGraph, final BlankNodeOrIRI subject, final IRI predicate,
      final RDFTerm object) {
    requireActive();
    final org.eclipse.rdf4j.model.Resource subjectValue = subject == null
        ? null
        : RDF4JConverters.toRDF4JResource(subject);
    final org.eclipse.rdf4j.model.IRI predicateValue = predicate == null ? null : RDF4JConverters.toRDF4JIRI(predicate);
    final org.eclipse.rdf4j.model.Value objectValue = object == null ? null : RDF4JConverters.toRDF4JValue(object);
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    return StorageErrors
        .translating(() -> connection.hasStatement(subjectValue, predicateValue, objectValue, false, context));
  }

  @Override
  public boolean ask(final String sparql) {
    return ask(sparql, Map.of());
  }

  @Override
  public boolean ask(final String sparql, final Map<String, RDFTerm> bindings) {
    requireActive();
    final BooleanQuery query = SparqlErrors
        .bound(SparqlErrors.translating(() -> connection.prepareBooleanQuery(QueryLanguage.SPARQL, sparql)), bindings);
    return SparqlErrors.translating(query::evaluate);
  }

  @Override
  public ReadableGraph construct(final String sparql) {
    return construct(sparql, Map.of());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Rejects an RDF 1.2 triple term in the result with an {@link IllegalStateException}: a SPARQL
   * update can store one, the port data model cannot represent it.</p>
   *
   * @throws IllegalStateException if the result holds an RDF 1.2 triple term
   */
  @Override
  public ReadableGraph construct(final String sparql, final Map<String, RDFTerm> bindings) {
    requireActive();
    final GraphQuery query = SparqlErrors
        .bound(SparqlErrors.translating(() -> connection.prepareGraphQuery(QueryLanguage.SPARQL, sparql)), bindings);
    final Model model = SparqlErrors.translating(() -> QueryResults.asModel(query.evaluate()));
    RDF4JConverters.requireNoTripleTerms(model);
    return new RDF4JGraph(model);
  }
}
