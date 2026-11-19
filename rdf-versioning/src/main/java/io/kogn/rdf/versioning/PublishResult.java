// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.kogn.rdf.terms.IRI;

/**
 * The outcome of a {@link ResourceVersioning#publish publish}: for each proxy IRI an
 * {@link EntryResult}, the history entry that names its current version and the token for
 * its next draft write.
 *
 * <p>The entry is the one the publish created; for a proxy IRI whose draft was
 * unchanged since its latest entry it is that existing entry, and nothing was written
 * for it. The map holds exactly the proxy IRIs that were published, in the order they
 * were passed.</p>
 *
 * @param results proxy IRI to its {@link EntryResult}
 */
public record PublishResult(Map<IRI, EntryResult> results) {

  /**
   * Creates the result, keeping an unmodifiable copy of the map.
   *
   * @param results proxy IRI to its entry result; must not be {@code null}
   * @throws NullPointerException if the argument is {@code null}
   */
  public PublishResult {
    results = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(results, "results")));
  }
}
