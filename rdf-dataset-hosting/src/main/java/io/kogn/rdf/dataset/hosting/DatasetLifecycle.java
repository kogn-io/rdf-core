// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset.hosting;

import java.util.Set;

/**
 * Backend-neutral lifecycle for datasets: open-or-create, close, delete and
 * enumerate, with built-in in-flight protection.
 *
 * <p>Datasets are addressed by an opaque {@link DatasetId}. A dataset is
 * obtained through {@link #acquire(DatasetId)}, which returns a leased
 * {@link DatasetHandle} and never exposes a backend-specific store type. The
 * lease is what makes eviction and deletion safe: while a handle is open the
 * underlying store cannot be torn down.</p>
 *
 * <p>This port is pure <em>mechanism</em>. Any idle/TTL eviction <em>policy</em>
 * lives with the consumer, which decides when to call {@link #close(DatasetId)}.
 * The on-create seeding hook is a construction concern of the backend
 * implementation, not a method of this port — the port carries no mutable
 * registration state.</p>
 */
public interface DatasetLifecycle {

  /**
   * Opens the dataset for {@code id}, creating it (and running the backend's
   * one-time on-create hook) if it does not yet exist, and takes a lease on it.
   *
   * <p>The returned {@link DatasetHandle} must be closed — ideally via
   * try-with-resources — to release the lease. While any lease is open the
   * dataset is protected from {@link #close(DatasetId)} and
   * {@link #delete(DatasetId)}. If the dataset is newly created the on-create
   * hook runs to completion before this method returns, so the caller never
   * observes an unseeded dataset.</p>
   *
   * <p>"One-time" is scoped to the store, not to the {@link DatasetId}: for an
   * {@code IN_MEMORY} dataset {@link #close(DatasetId)} destroys the store, so the
   * next {@code acquire} creates it anew and runs the hook again. See
   * {@link #close(DatasetId)} for the full contract.</p>
   *
   * <p>A {@link #delete(DatasetId)} that failed part-way through may leave a dataset
   * behind that is neither the old one nor a fresh one. An implementation must not
   * hand such remains out as though they were a whole dataset: it either clears them
   * away, so that this call creates the dataset anew, or it refuses the identifier
   * with an {@link IllegalStateException} for as long as they are there.</p>
   *
   * @param id the dataset identifier; must not be {@code null}
   * @return an open, leased handle to the dataset; never {@code null}
   * @throws IllegalStateException if a failed {@link #delete(DatasetId)} left remains
   *     that cannot be cleared away
   */
  DatasetHandle acquire(DatasetId id);

  /**
   * Evicts the dataset for {@code id}: shuts the backing store down and drops it
   * from the in-memory cache, <strong>without</strong> deleting its storage.
   *
   * <p>This is the eviction trigger a consumer's idle/TTL policy invokes. The
   * returned {@link DatasetCloseOutcome} tells the policy what happened:
   * {@link DatasetCloseOutcome#CLOSED} if the dataset was shut down,
   * {@link DatasetCloseOutcome#STILL_LEASED} if it currently has open leases —
   * this call is then a no-op and never interrupts in-flight work, the policy is
   * expected to retry later — or {@link DatasetCloseOutcome#NOT_OPEN} if the
   * lifecycle was not holding that dataset at all, which is not an error:
   * already evicted, never opened, and an id it has never seen all look the
   * same from here. A subsequent {@link #acquire(DatasetId)} re-opens the same
   * persisted dataset.</p>
   *
   * <p><strong>That resume guarantee holds only for a store with {@code PERSISTENT}
   * persistence.</strong> A dataset configured as {@code IN_MEMORY} has no storage to
   * resume: closing it discards its contents outright, and the next
   * {@link #acquire(DatasetId)} creates a fresh, empty dataset and re-runs the
   * on-create hook — the same outcome as {@link #delete(DatasetId)}. A consumer that
   * builds a generic idle/TTL eviction policy against this neutral port must treat
   * {@code close} as destructive for {@code IN_MEMORY} datasets.</p>
   *
   * @param id the dataset identifier; must not be {@code null}
   * @return what happened to the dataset; never {@code null}
   */
  DatasetCloseOutcome close(DatasetId id);

  /**
   * Deletes the dataset for {@code id}, including its on-disk storage.
   *
   * <p>Unlike {@link #close(DatasetId)} this is destructive: it throws if the
   * dataset still has open leases, so that a delete racing with in-flight use is
   * surfaced rather than silently corrupting an open store.</p>
   *
   * <p>Deleting a dataset's storage need not be atomic, so this call can fail with
   * the dataset half gone. What is left over is then no longer a dataset an
   * implementation may serve — see {@link #acquire(DatasetId)} for how that state is
   * resolved.</p>
   *
   * @param id the dataset identifier; must not be {@code null}
   * @throws IllegalStateException if the dataset has at least one open lease
   */
  void delete(DatasetId id);

  /**
   * Returns the identifiers of all known datasets — both currently open and
   * those persisted but not currently held in memory.
   *
   * @return the set of known dataset identifiers; never {@code null}
   */
  Set<DatasetId> list();
}
