// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset.hosting;

/**
 * What {@link DatasetMaintenance#clearUnfinishedDelete(DatasetId)} did under an identifier.
 *
 * <p>A maintenance run built against {@link DatasetMaintenance} needs to tell a cleanup it
 * really performed from one there was no call for: {@link #NOTHING_TO_CLEAR} is not an error —
 * it is what the run sees when another caller, or an {@link DatasetLifecycle#acquire(DatasetId)}
 * retrying the cleanup itself, got there first.</p>
 */
public enum DatasetCleanupOutcome {

  /** The remains of a failed delete were there and are now gone; nothing was created in their place. */
  CLEARED,

  /** The identifier carried neither a dataset nor the remains of a failed delete. */
  NOTHING_TO_CLEAR
}
