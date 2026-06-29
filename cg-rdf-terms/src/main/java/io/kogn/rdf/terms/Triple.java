// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

/**
 * Represents an RDF Triple (subject, predicate, object).
 *
 * <p>A triple is the basic unit of information in RDF, representing a statement
 * about a resource.</p>
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
