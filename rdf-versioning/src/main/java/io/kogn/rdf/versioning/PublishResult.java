// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.kogn.rdf.terms.IRI;

/**
 * The outcome of a {@link ResourceVersioning#publish publish}: for each proxy IRI the
 * history entry that names its current version, and the token for its next draft write.
 *
 * <p>The entry is the one the publish created; for a proxy IRI whose draft was
 * unchanged since its latest entry it is that existing entry, and nothing was written
 * for it. Both maps hold exactly the proxy IRIs that were published, in the order they
 * were passed.</p>
 *
 * @param entries proxy IRI to the {@code urn:uuid:} IRI of its history entry
 * @param tokens proxy IRI to the token to pass to its next
 *     {@link ResourceVersioning#replaceSlice replaceSlice}; a proxy IRI whose publish
 *     was a no-op gets the token it passed in
 */
public record PublishResult(Map<IRI, IRI> entries, Map<IRI, RevisionToken> tokens) {

  /**
   * Creates the result, keeping an unmodifiable copy of both maps.
   *
   * @param entries proxy IRI to entry IRI; must not be {@code null}
   * @param tokens proxy IRI to token; must not be {@code null}
   * @throws NullPointerException if an argument is {@code null}
   */
  public PublishResult {
    entries = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(entries, "entries")));
    tokens = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(tokens, "tokens")));
  }
}
