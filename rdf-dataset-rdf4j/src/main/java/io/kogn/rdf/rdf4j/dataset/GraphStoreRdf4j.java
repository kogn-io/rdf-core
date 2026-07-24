// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.rdf4j.RDF4JGraph;
import io.kogn.rdf.rdf4j.internal.RDF4JConverters;
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

  /**
   * Creates a graph store backed by the given RDF4J repository.
   *
   * @param repository the repository to open connections against
   */
  public GraphStoreRdf4j(final Repository repository) {
    this.repository = repository;
  }

  @Override
  public long add(final IRI namedGraph, final ReadableGraph triples) {
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    return inTransaction(conn -> {
      final long before = conn.size(context);
      triples.stream()
          .forEach(triple -> conn.add(RDF4JConverters.toRDF4JResource(triple.getSubject()),
              RDF4JConverters.toRDF4JIRI(triple.getPredicate()), RDF4JConverters.toRDF4JValue(triple.getObject()),
              context));
      return conn.size(context) - before;
    });
  }

  @Override
  public long remove(final IRI namedGraph, final ReadableGraph triples) {
    final org.eclipse.rdf4j.model.IRI context = RDF4JConverters.toRDF4JIRI(namedGraph);
    return inTransaction(conn -> {
      final long before = conn.size(context);
      triples.stream()
          .forEach(triple -> conn.remove(RDF4JConverters.toRDF4JResource(triple.getSubject()),
              RDF4JConverters.toRDF4JIRI(triple.getPredicate()), RDF4JConverters.toRDF4JValue(triple.getObject()),
              context));
      return before - conn.size(context);
    });
  }

  /**
   * Runs a batch mutation inside a single explicit transaction so that the whole
   * triple set is applied atomically (commit on success, rollback on any
   * {@link RuntimeException}). Without this, RDF4J auto-commits per statement,
   * which leaves a half-applied graph on failure and is slow for large batches.
   *
   * <p>The graph size is sampled before and after the mutation inside the same
   * transaction. A bare {@code conn.begin()} would run at the backend's default
   * isolation — {@code SNAPSHOT_READ} for both RDF4J's {@code MemoryStore} and
   * {@code NativeStore} — which only guarantees a single query result is
   * internally consistent, not that two reads in the same transaction (the
   * before- and after-sample) see the same snapshot; a concurrent commit to the
   * same named graph between the two samples would then leak into the delta.
   * The transaction is therefore opened at {@link IsolationLevels#SNAPSHOT}
   * instead, which does guarantee both samples see one consistent snapshot, so
   * the net delta returned to callers is race-free against concurrent writers to
   * the same named graph. {@code SNAPSHOT} rather than
   * {@code SERIALIZABLE} because this transaction never uses its reads as an
   * optimistic-concurrency guard for a later write — see
   * {@link DatasetTransactorRdf4j} for that stronger case.</p>
   */
  private long inTransaction(final java.util.function.ToLongFunction<RepositoryConnection> work) {
    try (RepositoryConnection conn = repository.getConnection()) {
      conn.begin(IsolationLevels.SNAPSHOT);
      try {
        final long result = work.applyAsLong(conn);
        conn.commit();
        return result;
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
