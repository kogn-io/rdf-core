// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

/**
 * What a {@link HistoryEntry} records (ADR-0022, ADR-0023).
 */
public enum EntryKind {

  /** A publish: the entry names the version it created or confirmed as current. */
  PUBLISH,

  /**
   * A tombstone: the proxy IRI was deleted. The entry names the version that was current
   * when the proxy IRI was deleted and the procedure that derived it, or neither if the
   * proxy IRI was never published. The version itself stays in the content store.
   */
  TOMBSTONE
}
