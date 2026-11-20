// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset.hosting;

import io.kogn.rdf.dataset.DatasetStorageException;

/**
 * Signals that {@link DatasetLifecycle#acquire(DatasetId)} could not open a dataset because its
 * store is locked by another owner — another lifecycle over the same storage, in this process or
 * in another one.
 *
 * <p>A store this library hosts is held open by the lifecycle that opened it, and a backend may
 * lock it against every other owner for as long as it is open. Two lifecycles over the same
 * storage therefore do not share a dataset: the second one fails with this exception as soon as
 * it acquires an identifier the first one holds open. It is not a lease conflict — open leases
 * of the <em>same</em> lifecycle never block {@code acquire} — and not a defect of the
 * dataset: the store is intact and opens again once its owner has shut it down.</p>
 *
 * <p>This is the neutral, backend-independent form of the backend's lock signal; the original is
 * kept as {@linkplain Throwable#getCause() cause}. It is a subtype of
 * {@link DatasetStorageException}, so a caller that handles every storage failure alike catches it
 * there, while a caller that wants to tell the user "another process holds this dataset" catches
 * exactly this type, without importing a backend type.</p>
 *
 * <p>When {@code acquire} fails this way, it has taken no lease, run no on-create hook and left
 * the locked store's storage untouched. Retrying is pointless while the other owner keeps the
 * store open; the usual remedy is to stop that owner, or to route every access to the storage
 * through one shared lifecycle.</p>
 */
public class DatasetStoreLockedException extends DatasetStorageException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception describing a dataset whose store is locked by another owner.
   *
   * @param message which dataset could not be opened, and why
   * @param cause the backend's original lock signal; may be {@code null}
   */
  public DatasetStoreLockedException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
