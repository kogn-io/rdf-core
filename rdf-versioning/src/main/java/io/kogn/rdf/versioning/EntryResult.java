// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.Objects;

import io.kogn.rdf.terms.IRI;

/**
 * What a {@link ResourceVersioning#publish publish} returns for one proxy IRI: the
 * history entry that names its current version, and the token for its next draft write.
 *
 * @param entryIRI the {@code urn:uuid:} IRI of the history entry; for a proxy IRI whose
 *     draft was unchanged since its latest entry, that existing entry
 * @param token the token to pass to the next {@link ResourceVersioning#replaceDraft
 *     replaceDraft} of the proxy IRI; the token that was passed in if the publish was a
 *     no-op for it
 */
public record EntryResult(IRI entryIRI, RevisionToken token) {

  /**
   * Creates the result.
   *
   * @param entryIRI the entry IRI; must not be {@code null}
   * @param token the next token; must not be {@code null}
   * @throws NullPointerException if an argument is {@code null}
   */
  public EntryResult {
    Objects.requireNonNull(entryIRI, "entryIRI");
    Objects.requireNonNull(token, "token");
  }
}
