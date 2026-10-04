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
   * Returns this term as <a href="https://www.w3.org/TR/n-triples/">RDF 1.1 N-Triples</a>: an IRI in
   * angle brackets, a literal quoted and escaped as the grammar requires with {@code @tag} or
   * {@code ^^<datatype>}, a blank node as {@code _:label}. All implementations produce the same string for
   * equal terms as defined by {@code equals}; the language tag is written as given, its case is preserved.
   *
   * @return the N-Triples representation
   * @throws IllegalArgumentException if the term cannot be written as N-Triples (an IRI with a character the
   *     {@code IRIREF} production forbids, a malformed language tag or blank node label, an unpaired UTF-16 surrogate); the message starts
   *     with {@code "N-Triples:"}. {@link Object#toString()} never throws.
   */
  String ntriplesString();
}
