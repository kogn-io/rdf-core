// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.rdf4j.RDF4JConverters;
import io.kogn.rdf.rdf4j.RDF4JGraph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * RDF4J-based implementation of {@link GraphStore}.
 *
 * <p>Each operation opens a dedicated {@link RepositoryConnection} from the
 * underlying {@link Repository} and closes it immediately after, following
 * the same pattern used by the existing service layer.</p>
 */
public class GraphStoreRdf4j implements GraphStore {

  private final Repository repository;

  public GraphStoreRdf4j(final Repository repository) {
    this.repository = repository;
  }

  @Override
  public void add(final IRI namedGraph, final ReadableGraph triples) {
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    inTransaction(conn -> triples.stream()
        .forEach(triple -> conn.add(RDF4JConverters.toRDF4JResource(triple.getSubject()),
            RDF4JConverters.toRDF4JIRI(triple.getPredicate()), RDF4JConverters.toRDF4JValue(triple.getObject()),
            context)));
  }

  @Override
  public void remove(final IRI namedGraph, final ReadableGraph triples) {
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    inTransaction(conn -> triples.stream()
        .forEach(triple -> conn.remove(RDF4JConverters.toRDF4JResource(triple.getSubject()),
            RDF4JConverters.toRDF4JIRI(triple.getPredicate()), RDF4JConverters.toRDF4JValue(triple.getObject()),
            context)));
  }

  /**
   * Runs a batch mutation inside a single explicit transaction so that the whole
   * triple set is applied atomically (commit on success, rollback on any
   * {@link RuntimeException}). Without this, RDF4J auto-commits per statement,
   * which leaves a half-applied graph on failure and is slow for large batches.
   */
  private void inTransaction(final java.util.function.Consumer<RepositoryConnection> work) {
    try (RepositoryConnection conn = repository.getConnection()) {
      conn.begin();
      try {
        work.accept(conn);
        conn.commit();
      } catch (RuntimeException e) {
        try {
          conn.rollback();
        } catch (RuntimeException rollbackEx) {
          e.addSuppressed(rollbackEx);
        }
        throw e;
      }
    }
  }

  @Override
  public void clear(final IRI namedGraph) {
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    try (RepositoryConnection conn = repository.getConnection()) {
      conn.clear(context);
    }
  }

  @Override
  public ReadableGraph export(final IRI namedGraph) {
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    try (RepositoryConnection conn = repository.getConnection()) {
      final Model model = new LinkedHashModel();
      try (RepositoryResult<Statement> result = conn.getStatements(null, null, null, false, context)) {
        result.forEach(model::add);
      }
      return new RDF4JGraph(model);
    }
  }

  @Override
  public long count(final IRI namedGraph) {
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    try (RepositoryConnection conn = repository.getConnection()) {
      return conn.size(context);
    }
  }

  @Override
  public long count() {
    try (RepositoryConnection conn = repository.getConnection()) {
      return conn.size();
    }
  }
}
