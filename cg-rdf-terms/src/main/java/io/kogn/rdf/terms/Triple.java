// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

/**
 * Represents an RDF Triple (subject, predicate, object).
 *
 * <p>A triple is the basic unit of information in RDF, representing a statement
 * about a resource.</p>
 *
 * <h2>Equality</h2>
 *
 * <p>Equality is defined component-wise. Following the
 * <a href="https://commons.apache.org/proper/commons-rdf/">Commons RDF</a> contract,
 * an implementation's {@link Object#equals(Object)} <strong>must</strong> return
 * {@code true} if and only if the other object is also a {@code Triple} and its
 * {@link #getSubject()}, {@link #getPredicate()} and {@link #getObject()} are each
 * equal to this triple's corresponding component (using the term equality defined by
 * {@link IRI}, {@link Literal} and {@link BlankNode}). {@link Object#hashCode()}
 * <strong>must</strong> be derived from those three components, e.g.
 * {@code java.util.Objects.hash(getSubject(), getPredicate(), getObject())}.</p>
 *
 * <p>Because blank-node references are only graph-local (see {@link BlankNode}), triples
 * containing blank nodes are only meaningfully comparable within the same graph.</p>
 */
public interface Triple {

  /**
   * Returns the subject of this triple.
   *
   * @return the subject (IRI or BlankNode)
   */
  BlankNodeOrIRI getSubject();

  /**
   * Returns the predicate of this triple.
   *
   * @return the predicate (always an IRI)
   */
  IRI getPredicate();

  /**
   * Returns the object of this triple.
   *
   * @return the object (IRI, Literal, or BlankNode)
   */
  RDFTerm getObject();
}
