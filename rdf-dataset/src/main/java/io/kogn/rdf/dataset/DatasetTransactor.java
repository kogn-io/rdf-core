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
 *
 * <p><strong>Isolation:</strong> implementations must serialize overlapping
 * transactions against the same dataset — two concurrent {@code inTransaction}
 * calls that observe (read) and then act on the same state must not both
 * succeed if a concurrently committed change would have invalidated what either
 * one observed. Concretely, if a caller reads a condition (e.g. "does this
 * business identifier already exist?") and writes based on it, and two such
 * calls race, at most one may commit unchanged; the other must fail with a
 * {@link ConcurrencyConflictException} rather than silently commit on stale
 * state. This is what makes an {@code ASK}-then-write guard inside {@code work}
 * safe under concurrency: a losing transaction is rejected loudly at commit
 * time, not merged silently. Callers that rely on such a guard should catch
 * {@link ConcurrencyConflictException} and retry the whole
 * {@code inTransaction} call — see that class for the retry loop. The
 * exception is part of this port: an implementation must translate whatever its
 * backend raises, so a caller never has to name a backend type to act on the
 * guarantee.</p>
 *
 * <p>That paragraph states what an implementation is <em>required</em> to provide,
 * not what every backend delivers today: the RDF4J implementation meets it for
 * guards over data the store already holds, but not reliably for a guard against a
 * resource that does not exist yet. Before using an {@code ASK}-then-write guard as
 * a uniqueness gate, read the "Limits of that guarantee" section on the
 * implementation you bind to.</p>
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
   * @throws ConcurrencyConflictException if the commit was rejected because this
   *     transaction lost a race against a concurrently committed, conflicting
   *     transaction on the same dataset (see class Javadoc); nothing was written
   * @throws RuntimeException re-thrown unchanged from {@code work} after rollback
   */
  <T> T inTransaction(Function<DatasetTx, T> work);
}
