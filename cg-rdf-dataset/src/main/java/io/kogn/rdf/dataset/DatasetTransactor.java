// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

import java.util.function.Function;

/**
 * Atomic unit-of-work boundary for dataset operations.
 *
 * <p>Wraps a block of {@link DatasetTx} operations in a single, all-or-nothing
 * transaction. If the {@code work} function throws any {@link RuntimeException}
 * (or {@link Error}) the transaction is rolled back; otherwise it is committed.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * IRI result = transactor.inTransaction(tx -> {
 *     tx.add(graphIri, triples);
 *     tx.update("DELETE WHERE { ... }");
 *     return computeResultIri();
 * });
 * }</pre>
 *
 * <p>Implementations must not allow nested transactions. Callers must not retain
 * a reference to the {@link DatasetTx} instance after the function returns.</p>
 */
public interface DatasetTransactor {

  /**
   * Executes {@code work} within an atomic transaction and returns its result.
   *
   * <p>The supplied {@link DatasetTx} is valid only for the duration of the call.
   * All operations performed via the transaction are committed atomically on success,
   * or rolled back completely if {@code work} throws.</p>
   *
   * @param <T> the result type produced by {@code work}
   * @param work the operations to execute; must not be {@code null}
   * @return the value returned by {@code work}
   * @throws RuntimeException re-thrown from {@code work} after rollback
   */
  <T> T inTransaction(Function<DatasetTx, T> work);
}
