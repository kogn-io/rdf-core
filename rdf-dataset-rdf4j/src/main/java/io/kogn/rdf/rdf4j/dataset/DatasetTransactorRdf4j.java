// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.util.function.Function;

import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.sail.SailConflictException;

import io.kogn.rdf.dataset.ConcurrencyConflictException;
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
 * <p><strong>Nesting:</strong> the port forbids a nested {@code inTransaction} call
 * (see {@link DatasetTransactor} class Javadoc); this implementation enforces it with a
 * {@link ThreadLocal} flag set on entry and cleared in a {@code finally} block, so a
 * nested call on the same thread — which would otherwise silently open a second,
 * independent connection and transaction — fails fast with an
 * {@link IllegalStateException} instead. The flag is per-thread, so a pooled thread
 * that later calls {@link #inTransaction} again, or a different thread, is unaffected.</p>
 *
 * <p><strong>Isolation:</strong> {@code SERIALIZABLE} is requested explicitly
 * rather than relying on the backend's default (RDF4J's {@code MemoryStore} and
 * {@code NativeStore} both default to {@code SNAPSHOT_READ}, which only
 * guarantees a single query result is internally consistent — it neither detects
 * nor prevents two overlapping transactions from both passing the same
 * application-level guard, e.g. an {@code ASK} check, and then both committing
 * conflicting writes). Under {@code SERIALIZABLE}, RDF4J tracks the statement
 * patterns a transaction read and fails the <em>later</em> commit with a
 * {@link SailConflictException} — reaching this class wrapped in a
 * {@link RepositoryException} — if a concurrently committed transaction changed the
 * state of a pattern this transaction observed. Callers that use
 * {@code inTransaction} as an optimistic-concurrency guard (read a condition, then
 * write based on it) can rely on this: the losing side of a race fails loudly
 * instead of silently double-committing.</p>
 *
 * <p>Such a failed commit is rethrown as the port's neutral
 * {@link ConcurrencyConflictException}, with the {@link RepositoryException} kept as
 * cause — so a retry loop catches an {@code io.kogn} type rather than an RDF4J one.
 * Every other commit failure, and every exception the work function itself threw,
 * passes through unchanged.</p>
 *
 * <p><strong>Limits of that guarantee (measured, RDF4J 6.0.0 + {@code MemoryStore}):</strong>
 * a SPARQL guard read whose IRIs are not yet known to the store — the "is this
 * brand-new resource already taken?" case — is not reliably conflict-protected. In a
 * two-thread race where both guards run before either write, both transactions
 * committed in a single-digit to low double-digit percentage of 1000 runs — 6% and 12%
 * on two machines, so treat the rate as timing-dependent rather than as a constant —
 * leaving the duplicate the guard was meant to prevent. The same race detects the
 * conflict in 1000 of 1000 runs as soon as the guard's subject, predicate and graph IRIs
 * are already present in the store (any earlier statement mentioning them suffices), and
 * likewise when the guard reads through {@code RepositoryConnection#hasStatement}
 * instead of SPARQL. So: a SPARQL guard is optimistic concurrency that holds for updates
 * to existing data, and must not be leaned on as the sole uniqueness gate for first-time
 * inserts. Tracked in
 * <a href="https://github.com/kogn-io/rdf-core/issues/23">issue 23</a>.</p>
 *
 * <p>The cause is in RDF4J, not in this class or in the port. The observation
 * <em>is</em> registered in the failing runs; what differs is value interning. Evaluating
 * a SPARQL statement pattern interns its constants into the store's own value registry,
 * and two threads interning the same unknown IRI concurrently can each end up with their
 * own instance, because the registry's duplicate recovery cannot fire. Conflict detection
 * then walks the statement list of the wrong instance, finds it empty, and both commits
 * pass. A guard that only looks values up never enters that path. The analysis, with a
 * standalone reproducer, is in issue 23.</p>
 *
 * <p><strong>Write the guard with
 * {@link DatasetTx#contains(io.kogn.rdf.terms.IRI, io.kogn.rdf.terms.BlankNodeOrIRI,
 * io.kogn.rdf.terms.IRI, io.kogn.rdf.terms.RDFTerm) DatasetTx#contains} instead.</strong>
 * It states the statement pattern directly and is implemented here via
 * {@code RepositoryConnection#hasStatement}, which looks values up rather than interning
 * them — so the conflict is detected even for IRIs the store has never seen, the case
 * measured at 1000 of 1000 above. An {@code ASK} guard remains a legitimate read; it is
 * only the <em>first-insert uniqueness</em> use of it that this backend does not
 * protect.</p>
 */
public class DatasetTransactorRdf4j implements DatasetTransactor {

  private static final ThreadLocal<Boolean> IN_TRANSACTION = ThreadLocal.withInitial(() -> false);

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
    if (IN_TRANSACTION.get()) {
      throw new IllegalStateException("nested transactions are not supported");
    }
    IN_TRANSACTION.set(true);
    try {
      return doInTransaction(work);
    } finally {
      IN_TRANSACTION.set(false);
    }
  }

  private <T> T doInTransaction(final Function<DatasetTx, T> work) {
    try (RepositoryConnection conn = repository.getConnection()) {
      conn.begin(IsolationLevels.SERIALIZABLE);
      try {
        final T result = work.apply(new DatasetTxRdf4j(conn));
        try {
          conn.commit();
        } catch (RuntimeException e) {
          throw translateConflict(e);
        }
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

  /**
   * Maps a failed commit to the port's neutral conflict type, if that is what it was.
   *
   * <p>RDF4J reports a lost {@code SERIALIZABLE} race as a {@link SailConflictException},
   * which reaches this class wrapped in a {@link RepositoryException}. Anything else is a
   * genuine commit failure and is passed through unchanged.</p>
   *
   * @param commitFailure the exception {@code commit()} threw
   * @return the exception to throw on: a {@link ConcurrencyConflictException} for a lost race,
   *     otherwise {@code commitFailure} itself
   */
  private static RuntimeException translateConflict(final RuntimeException commitFailure) {
    for (Throwable t = commitFailure; t != null; t = t.getCause()) {
      if (t instanceof SailConflictException) {
        return new ConcurrencyConflictException("commit rejected: transaction lost an optimistic-concurrency race",
            commitFailure);
      }
    }
    return commitFailure;
  }
}
