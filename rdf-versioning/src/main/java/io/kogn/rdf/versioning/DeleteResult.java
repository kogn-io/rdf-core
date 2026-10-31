// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.Objects;

import io.kogn.rdf.terms.IRI;

/**
 * The outcome of a {@link ResourceVersioning#delete delete}: the tombstone entry in the
 * history and the token for the next write of the proxy IRI.
 *
 * @param entry the {@code urn:uuid:} IRI of the tombstone entry; for a proxy IRI that was
 *     deleted already and has no draft since, the existing tombstone
 * @param token the token to pass to the next write of the proxy IRI; the token that was
 *     passed in if the delete was a no-op
 */
public record DeleteResult(IRI entry, RevisionToken token) {

  /**
   * Creates the result.
   *
   * @param entry the tombstone entry IRI; must not be {@code null}
   * @param token the next token; must not be {@code null}
   * @throws NullPointerException if an argument is {@code null}
   */
  public DeleteResult {
    Objects.requireNonNull(entry, "entry");
    Objects.requireNonNull(token, "token");
  }
}
