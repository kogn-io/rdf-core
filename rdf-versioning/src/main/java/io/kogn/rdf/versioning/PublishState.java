// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

/**
 * Where the draft of a proxy IRI stands against its published versions, as answered by
 * {@link ResourceVersioning#publishState}.
 *
 * <p>Decided by comparing CIDs, not triples: blank-node labels of the draft and of the
 * published view may differ for the same content (ADR-0022).</p>
 */
public enum PublishState {

  /** The proxy IRI has no history entry: it was never published. */
  NEVER_PUBLISHED,

  /** The CID of the draft equals the CID of the latest history entry. */
  PUBLISHED_UNCHANGED,

  /**
   * The draft differs from the latest published version: its CID is another, the draft is
   * empty, or the latest entry was derived by another procedure than the one in use. For
   * now the stored CID is compared as is, so that counts as changed (the same call a
   * publish makes); once a second procedure exists, the stored version is derived again
   * under the current procedure and compared (ADR-0022).
   */
  PUBLISHED_WITH_UNPUBLISHED_CHANGES,

  /**
   * The latest history entry is a tombstone: the proxy IRI was deleted and has no
   * published version. A draft written since is not published; publishing it ends this
   * state.
   */
  DELETED
}
