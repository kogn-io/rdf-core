package io.kogn.rdf.rdf4j.dataset;

import java.util.function.Function;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;

/**
 * RDF4J-based implementation of {@link DatasetTransactor}.
 *
 * <p>Opens a single {@link RepositoryConnection} per call to
 * {@link #inTransaction}, begins a transaction, hands a {@link DatasetTxRdf4j}
 * (bound to that connection) to the work function, and commits on success or
 * rolls back on any {@link RuntimeException}. The connection is always closed
 * after the transaction, regardless of outcome.</p>
 *
 * <p>The transaction pattern mirrors the batch path in
 * {@code GraphCommandServiceImpl.saveDraftAndPublishBatch}.</p>
 */
public class DatasetTransactorRdf4j implements DatasetTransactor {

  private final Repository repository;

  public DatasetTransactorRdf4j(final Repository repository) {
    this.repository = repository;
  }

  @Override
  public <T> T inTransaction(final Function<DatasetTx, T> work) {
    try (RepositoryConnection conn = repository.getConnection()) {
      conn.begin();
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
