// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

import java.util.Objects;

/**
 * Simple value-based implementation of {@link IRI}.
 *
 * <p>This record provides a lightweight IRI implementation that does not depend
 * on any specific RDF backend (RDF4J, Jena, etc.). It is used internally by
 * {@link SimpleRdf} and vocabulary constants.</p>
 *
 * @param iri the IRI string; must be non-null and absolute
 */
public record SimpleIRI(String iri) implements IRI {

  /**
   * Validates the IRI string.
   *
   * @throws NullPointerException if {@code iri} is {@code null}
   * @throws IllegalArgumentException if {@code iri} is not an absolute IRI (empty, or without a {@code :})
   */
  public SimpleIRI {
    Objects.requireNonNull(iri, "iri must not be null");
    if (iri.indexOf(':') < 0) {
      throw new IllegalArgumentException("Not a valid (absolute) IRI: " + iri);
    }
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalArgumentException if the IRI string contains a character the N-Triples {@code IRIREF}
   *     production forbids (space and control characters, {@code <>"{}|^`\})
   */
  @Override
  public String ntriplesString() {
    return NTriples.iri(iri);
  }

  @Override
  public String getIRIString() {
    return iri;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (!(obj instanceof IRI other))
      return false;
    return iri.equals(other.getIRIString());
  }

  @Override
  public int hashCode() {
    return iri.hashCode();
  }

  @Override
  public String toString() {
    return iri;
  }
}
