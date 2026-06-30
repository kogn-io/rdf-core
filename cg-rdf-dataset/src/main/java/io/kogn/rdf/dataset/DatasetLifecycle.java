// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

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
 * The one-time on-create seeding hook is a construction concern of the backend
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
   * @param id the dataset identifier; must not be {@code null}
   * @return an open, leased handle to the dataset; never {@code null}
   */
  DatasetHandle acquire(DatasetId id);

  /**
   * Evicts the dataset for {@code id}: shuts the backing store down and drops it
   * from the in-memory cache, <strong>without</strong> deleting its storage.
   *
   * <p>This is the eviction trigger a consumer's idle/TTL policy invokes. If the
   * dataset currently has open leases this call is a no-op (the policy is
   * expected to retry later); it never interrupts in-flight work. A subsequent
   * {@link #acquire(DatasetId)} re-opens the same persisted dataset.</p>
   *
   * @param id the dataset identifier; must not be {@code null}
   */
  void close(DatasetId id);

  /**
   * Deletes the dataset for {@code id}, including its on-disk storage.
   *
   * <p>Unlike {@link #close(DatasetId)} this is destructive: it throws if the
   * dataset still has open leases, so that a delete racing with in-flight use is
   * surfaced rather than silently corrupting an open store.</p>
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
