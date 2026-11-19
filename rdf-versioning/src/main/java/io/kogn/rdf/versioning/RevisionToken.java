// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.Objects;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.SimpleIRI;

/**
 * The optimistic-concurrency token of one proxy IRI (ADR-0022).
 *
 * <p>Opaque to the caller: it is obtained from {@link DraftSlice#token()} or from a
 * successful {@link ResourceVersioning#replaceDraft replaceDraft} and handed back to the
 * next write. It carries the proxy IRI and the revision counter the writer read; callers
 * compare tokens only for equality and must not interpret them or build their own.</p>
 *
 * <p>The counter exists for an empty or brand-new draft as well. It sees writes made
 * through the library only: a triple written to the content graph past the slice
 * operations does not change it.</p>
 */
public final class RevisionToken {

  private final String proxyIRI;
  private final long revision;

  RevisionToken(final IRI proxyIRI, final long revision) {
    this.proxyIRI = Objects.requireNonNull(proxyIRI, "proxyIRI").getIRIString();
    this.revision = revision;
  }

  /**
   * Returns the proxy IRI this token belongs to.
   *
   * @return the proxy IRI; never {@code null}
   */
  public IRI proxyIRI() {
    return new SimpleIRI(proxyIRI);
  }

  long revision() {
    return revision;
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof RevisionToken token && token.revision == revision && token.proxyIRI.equals(proxyIRI);
  }

  @Override
  public int hashCode() {
    return Objects.hash(proxyIRI, revision);
  }

  @Override
  public String toString() {
    return "RevisionToken[" + proxyIRI + " @ " + revision + "]";
  }
}
