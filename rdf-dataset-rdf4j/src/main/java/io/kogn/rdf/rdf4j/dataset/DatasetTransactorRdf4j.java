// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.util.function.Function;

import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.sail.SailConflictException;

import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;

/**
 * RDF4J-based implementation of {@link DatasetTransactor}.
 *
 * <p>Opens a single {@link RepositoryConnection} per call to
 * {@link #inTransaction}, begins a transaction at
 * {@link IsolationLevels#SERIALIZABLE}, hands a {@link DatasetTxRdf4j} (bound to
 * that connection) to the work function, and commits on success or rolls back on
 * any {@link RuntimeException}. The connection is always closed after the
 * transaction, regardless of outcome.</p>
 *
 * <p><strong>Isolation:</strong> {@code SERIALIZABLE} is requested explicitly
 * rather than relying on the backend's default (RDF4J's {@code MemoryStore} and
 * {@code NativeStore} both default to {@code SNAPSHOT_READ}, which only
 * guarantees a single query result is internally consistent — it neither detects
 * nor prevents two overlapping transactions from both passing the same
 * application-level guard, e.g. an {@code ASK} check, and then both committing
 * conflicting writes). Under {@code SERIALIZABLE}, RDF4J tracks the statement
 * patterns a transaction read and fails the <em>later</em> commit with a
 * {@link SailConflictException} — surfaced here as a {@link RepositoryException}
 * whose cause is the {@link SailConflictException} — if a concurrently committed
 * transaction changed the state of a pattern this transaction observed. Callers
 * that use {@code inTransaction} as an optimistic-concurrency guard (read a
 * condition, then write based on it) can rely on this: the losing side of a race
 * fails loudly instead of silently double-committing, and can catch
 * {@link RepositoryException} to retry the whole {@code inTransaction} call.</p>
 *
 * <p>The transaction pattern mirrors the batch path in
 * {@code GraphCommandServiceImpl.saveDraftAndPublishBatch}.</p>
 */
public class DatasetTransactorRdf4j implements DatasetTransactor {

  private final Repository repository;

  /**
   * Creates a transactor backed by the given RDF4J repository.
   *
   * @param repository the repository to open transactional connections against
   */
  public DatasetTransactorRdf4j(final Repository repository) {
    this.repository = repository;
  }

  @Override
  public <T> T inTransaction(final Function<DatasetTx, T> work) {
    try (RepositoryConnection conn = repository.getConnection()) {
      conn.begin(IsolationLevels.SERIALIZABLE);
      try {
        final T result = work.apply(new DatasetTxRdf4j(conn));
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
}
