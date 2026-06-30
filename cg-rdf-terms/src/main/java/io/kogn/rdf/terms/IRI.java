// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

/**
 * Represents an IRI (Internationalized Resource Identifier) in RDF.
 *
 * <p>An IRI is a globally unique identifier for resources in RDF graphs.
 * It extends the concept of URI to support international characters.</p>
 *
 * <h2>Equality</h2>
 *
 * <p>Equality is defined by the IRI string and is independent of the implementing
 * class, so IRIs from different backends are comparable. Following the
 * <a href="https://commons.apache.org/proper/commons-rdf/">Commons RDF</a> contract,
 * an implementation's {@link Object#equals(Object)} <strong>must</strong> return
 * {@code true} if and only if the other object is also an {@code IRI} and its
 * {@link #getIRIString()} is a code-point-for-code-point equal {@link String}
 * (no IRI normalisation or {@code %}-escaping is applied). {@link Object#hashCode()}
 * <strong>must</strong> equal {@code getIRIString().hashCode()}.</p>
 */
public interface IRI extends BlankNodeOrIRI {
  /**
   * Returns the IRI string representation.
   *
   * @return the full IRI as string
   */
  String getIRIString();
}
