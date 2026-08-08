// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset.hosting;

/**
 * What {@link DatasetLifecycle#close(DatasetId)} did with the dataset.
 *
 * <p>An idle/TTL eviction policy built against {@link DatasetLifecycle} needs to
 * tell these three outcomes apart: only {@link #STILL_LEASED} calls for a retry,
 * and {@link #NOT_OPEN} is not an error — it is what the policy sees when it
 * evicts an identifier that is already gone.</p>
 */
public enum DatasetCloseOutcome {

  /** The dataset had no open lease and was shut down. */
  CLOSED,

  /** At least one lease was open; the call was a no-op and the policy should retry later. */
  STILL_LEASED,

  /** The lifecycle was not holding this dataset — already evicted, never opened, or unknown. */
  NOT_OPEN
}
