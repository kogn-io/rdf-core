// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset.hosting;

import java.util.Set;

/**
 * Maintenance of what a failed {@link DatasetLifecycle#delete(DatasetId)} left behind: find the
 * identifiers that carry such remains, and clear them away.
 *
 * <p>A delete can fail with the dataset half gone. {@link DatasetLifecycle} then refuses the
 * identifier and leaves it out of {@link DatasetLifecycle#list()}, so through that port the
 * remains are invisible, and the only way to retry the cleanup is an
 * {@link DatasetLifecycle#acquire(DatasetId) acquire} — which, once the cleanup succeeds,
 * creates and seeds a new dataset. This port is for a caller that has to account for the remains
 * instead: a deletion obligation it must meet, a maintenance run.</p>
 *
 * <p>It is a port of its own rather than more methods on {@link DatasetLifecycle}: clearing up
 * after a failed delete is maintenance, not working with a dataset, and a consumer that never runs
 * maintenance never sees it (see ADR-0018). An implementation of {@link DatasetLifecycle} that can
 * leave remains behind implements this port alongside it, and both views must be taken from the
 * same instance — they describe one pool of datasets.</p>
 */
public interface DatasetMaintenance {

  /**
   * Returns the identifiers that carry nothing but the remains of a failed
   * {@link DatasetLifecycle#delete(DatasetId)}.
   *
   * <p>These are exactly the identifiers {@link DatasetLifecycle#acquire(DatasetId)} refuses or
   * would first have to clean up, and that {@link DatasetLifecycle#list()} leaves out; the two
   * listings never overlap. What another caller does concurrently is not frozen by this call.</p>
   *
   * <p>The result is all-or-nothing: if the storage cannot be read, this call fails rather than
   * return a partial set, which a caller could not tell from a complete one.</p>
   *
   * @return the identifiers carrying the remains of a failed delete; never {@code null}
   * @throws RuntimeException if the storage cannot be read
   */
  Set<DatasetId> listUnfinishedDeletes();

  /**
   * Clears away the remains of a failed {@link DatasetLifecycle#delete(DatasetId)} under
   * {@code id}, creating nothing in their place.
   *
   * <p>Afterwards the identifier is an unknown one again: the next
   * {@link DatasetLifecycle#acquire(DatasetId)} creates and seeds a fresh dataset, as for any
   * identifier never seen before.</p>
   *
   * <p>This call only ever removes remains. An intact dataset under {@code id} — open or merely
   * persisted — is refused with an {@link IllegalStateException} and left alone: getting rid of a
   * dataset is {@link DatasetLifecycle#delete(DatasetId)}, the one path that guards against open
   * leases. An identifier carrying neither a dataset nor remains is not an error; it is reported as
   * {@link DatasetCleanupOutcome#NOTHING_TO_CLEAR}.</p>
   *
   * <p>If the cleanup fails again, this call exits with the backend's exception and the identifier
   * stays barred, still named by {@link #listUnfinishedDeletes()}; the caller may retry later.</p>
   *
   * @param id the dataset identifier; must not be {@code null}
   * @return what was done under {@code id}; never {@code null}
   * @throws NullPointerException if {@code id} is {@code null}
   * @throws IllegalStateException if {@code id} names an intact dataset
   * @throws RuntimeException if clearing the remains fails
   */
  DatasetCleanupOutcome clearUnfinishedDelete(DatasetId id);
}
