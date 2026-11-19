// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset.hosting;

/**
 * What {@link DatasetMaintenance#clearDeletionLeftover(DatasetId)} did under an identifier.
 *
 * <p>A maintenance run built against {@link DatasetMaintenance} needs to tell a cleanup it
 * really performed from one there was no call for: {@link #NOTHING_TO_CLEAR} is not an error —
 * it is what the run sees when another caller, or an {@link DatasetLifecycle#acquire(DatasetId)}
 * retrying the cleanup itself, got there first.</p>
 */
public enum DatasetCleanupOutcome {

  /** A deletion leftover was there and is now gone; nothing was created in its place. */
  CLEARED,

  /** The identifier carried neither a dataset nor a deletion leftover. */
  NOTHING_TO_CLEAR
}
