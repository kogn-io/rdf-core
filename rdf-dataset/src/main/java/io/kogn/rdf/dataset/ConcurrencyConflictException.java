// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * Signals that a transaction lost an optimistic-concurrency race and was rejected.
 *
 * <p>Thrown by {@link DatasetTransactor#inTransaction} when the transaction could not
 * be committed because a concurrently committed transaction on the same dataset
 * changed state this one had observed. Nothing of the losing transaction was written:
 * it was rolled back in full.</p>
 *
 * <p>This is the neutral, backend-independent form of the isolation guarantee stated on
 * {@link DatasetTransactor}. Implementations translate their backend's conflict signal
 * into it and keep the original as {@linkplain Throwable#getCause() cause}, so a caller
 * can act on the guarantee without catching a backend type — and without the
 * {@code catch (RuntimeException)} that would also swallow a bug in its own work
 * function.</p>
 *
 * <p>The failure is transient by nature: the state the transaction read has moved on,
 * so re-running the whole unit of work — guard read included — is the intended
 * response.</p>
 *
 * <pre>{@code
 * for (int attempt = 0; ; attempt++) {
 *   try {
 *     return transactor.inTransaction(tx -> {
 *       if (tx.contains(graph, subject, predicate, null)) {
 *         throw new IllegalStateException("already taken");
 *       }
 *       tx.add(graph, triples);
 *       return subject;
 *     });
 *   } catch (ConcurrencyConflictException e) {
 *     if (attempt == MAX_ATTEMPTS) {
 *       throw e;
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Retry the call, not the {@link DatasetTx} — the transaction object handed to the
 * failed attempt is dead. Bound the attempts: a guard that keeps losing against a
 * steady stream of writers will not converge on its own.</p>
 */
public class ConcurrencyConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a conflict exception describing a rejected commit.
   *
   * @param message what was being attempted when the conflict was detected
   * @param cause the backend's original conflict signal; may be {@code null}
   */
  public ConcurrencyConflictException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
