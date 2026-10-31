// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.Objects;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * The draft slice of a proxy IRI as read, with the token for the next write.
 *
 * <p>The graph holds every triple of the content graph whose subject is the proxy IRI or
 * one of its {@code #fragment} IRIs, plus the blank nodes reachable from them
 * (ADR-0022). It is empty if the proxy IRI has no draft; an empty draft is not a
 * deletion. Blank-node labels are the store's and not stable across reads.</p>
 *
 * @param proxyIri the proxy IRI that was read
 * @param triples the slice, a copy the caller may keep
 * @param token the token to pass to the next {@link ResourceVersioning#replaceSlice
 *     replaceSlice} of this proxy IRI
 */
public record DraftSlice(IRI proxyIri, ReadableGraph triples, RevisionToken token) {

  /**
   * Creates the slice.
   *
   * @param proxyIri the proxy IRI; must not be {@code null}
   * @param triples the slice; must not be {@code null}
   * @param token the token; must not be {@code null}
   * @throws NullPointerException if an argument is {@code null}
   */
  public DraftSlice {
    Objects.requireNonNull(proxyIri, "proxyIri");
    Objects.requireNonNull(triples, "triples");
    Objects.requireNonNull(token, "token");
  }
}
