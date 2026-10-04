// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * Signals that the store failed while carrying out a graph operation or committing a transaction.
 *
 * <p>Thrown by the graph operations of {@link GraphStore} — and therefore of {@link DatasetTx},
 * which inherits them — and by {@link DatasetTx#contains}, when the backend could not do its
 * work: an I/O error of a file-backed store, a store that is shut down or locked, a failure
 * while reading the result. It is also thrown by {@link DatasetTransactor#inTransaction} when
 * the commit fails for any reason other than a lost race, which is reported as the more
 * specific {@link ConcurrencyConflictException}.</p>
 *
 * <p>This is the neutral, backend-independent form of a storage failure, the counterpart of
 * {@link SparqlEvaluationException} for the graph operations. Implementations translate their
 * backend's failure signal into it and keep the original as {@linkplain Throwable#getCause()
 * cause}, so a caller can handle a failing store without importing a backend type through a
 * port whose purpose is to keep the backend out of the consumer.</p>
 *
 * <p>What the type does <em>not</em> cover: a {@code null} argument ({@link NullPointerException}),
 * an RDF 1.2 triple term in a stored graph ({@link IllegalStateException}, see
 * {@link GraphStore#export}), and the use of a {@link DatasetTx} after its transaction has ended
 * ({@link IllegalStateException}, see {@link DatasetTx}) are defects of the call, not failures
 * of the store, and keep their own types. In particular a retry is pointless for them.</p>
 *
 * <p>Whether a retry of a storage failure can succeed depends on what failed, which the type
 * alone does not say; inspect the message or the cause. A failed {@link GraphStore#add} or
 * {@link GraphStore#remove} outside a transaction is rolled back; inside
 * {@link DatasetTransactor#inTransaction} the exception rolls the whole unit of work back like
 * any other.</p>
 */
public class DatasetStorageException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a storage exception describing a failed graph operation or commit.
   *
   * @param message what went wrong in the store
   * @param cause the backend's original failure signal; may be {@code null}
   */
  public DatasetStorageException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
