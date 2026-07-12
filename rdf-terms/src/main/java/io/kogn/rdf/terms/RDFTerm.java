// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

/**
 * Base interface for all RDF terms (IRI, Literal, BlankNode).
 *
 * <p>An RDF term is a component that can appear in RDF triples as subject,
 * predicate, or object.</p>
 */
public interface RDFTerm {

  /**
   * Returns a string representation of this RDF term in N-Triples format.
   *
   * @return the N-Triples representation
   */
  String ntriplesString();
}
