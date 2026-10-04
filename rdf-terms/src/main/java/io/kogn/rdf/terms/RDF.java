// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

import java.util.List;

/**
 * Factory interface for creating RDF terms and graphs.
 *
 * <p>This is the main entry point for creating RDF objects. Implementations
 * provide the concrete backend (e.g., RDF4J, Apache Jena).</p>
 *
 * <p>Every implementation rejects the same arguments the same way: {@code null} with a
 * {@link NullPointerException}, an empty or malformed value with an {@link IllegalArgumentException}
 * (see the {@code @throws} of each factory method). A test backend must not accept more than production.
 * Whether a term can be written as N-Triples is checked by {@link RDFTerm#ntriplesString()}, not here.</p>
 */
public interface RDF extends IRIFactory {

  /**
   * Creates a new IRI from a string.
   *
   * @param iri the IRI string
   * @return the IRI instance
   * @throws NullPointerException if {@code iri} is {@code null}
   * @throws IllegalArgumentException if {@code iri} is not an absolute IRI (empty, or without a {@code :})
   */
  @Override
  IRI createIRI(String iri);

  /**
   * Creates a new literal with a string value.
   *
   * @param lexicalForm the string value; the empty string is allowed
   * @return the literal instance
   * @throws NullPointerException if {@code lexicalForm} is {@code null}
   */
  Literal createLiteral(String lexicalForm);

  /**
   * Creates a new literal with a string value and language tag.
   *
   * @param lexicalForm the string value
   * @param languageTag the language tag (e.g., "en", "de")
   * @return the literal instance
   * @throws NullPointerException if {@code lexicalForm} or {@code languageTag} is {@code null}
   * @throws IllegalArgumentException if {@code languageTag} is empty
   */
  Literal createLiteral(String lexicalForm, String languageTag);

  /**
   * Creates a new typed literal.
   *
   * @param lexicalForm the string value
   * @param datatype the datatype IRI; {@code rdf:langString} and {@code rdf:dirLangString} are rejected,
   *     they need a language tag (see {@link #createLiteral(String, String)})
   * @return the literal instance
   * @throws NullPointerException if {@code lexicalForm} or {@code datatype} is {@code null}
   * @throws IllegalArgumentException if {@code datatype} is {@code rdf:langString} or
   *     {@code rdf:dirLangString}
   */
  Literal createLiteral(String lexicalForm, IRI datatype);

  /**
   * Creates a new blank node.
   *
   * @return a new blank node with a unique identifier
   */
  BlankNode createBlankNode();

  /**
   * Creates a new blank node with a specific identifier.
   *
   * <p>{@link BlankNode#uniqueReference()} of the returned node equals {@code identifier}.
   * Callers that skolemize or otherwise round-trip blank node identifiers through this method
   * rely on that identity to stay stable across implementations.</p>
   *
   * @param identifier the identifier
   * @return the blank node instance
   * @throws NullPointerException if {@code identifier} is {@code null}
   * @throws IllegalArgumentException if {@code identifier} is empty
   */
  BlankNode createBlankNode(String identifier);

  /**
   * Creates a new triple.
   *
   * @param subject the subject
   * @param predicate the predicate
   * @param object the object
   * @return the triple instance
   * @throws NullPointerException if {@code subject}, {@code predicate} or {@code object} is {@code null}
   */
  Triple createTriple(BlankNodeOrIRI subject, IRI predicate, RDFTerm object);

  /**
   * Creates a new empty graph.
   *
   * @return a new graph instance
   */
  Graph createGraph();

  /**
   * Creates an RDF list (collection) from a list of RDF terms.
   *
   * <p>The graph of the result holds exactly the {@code rdf:first}/{@code rdf:rest} chain, one
   * fresh blank node per item, the last {@code rdf:rest} pointing to {@code rdf:nil}: two triples
   * per item. No {@code rdf:type rdf:List} triple is added, so every implementation yields the
   * same triple set up to the identity of the blank nodes. List items may be any RDF term
   * (IRIs, literals, or blank nodes).</p>
   *
   * @param items the RDF terms to include in the list
   * @return an RDF list whose head is the first chain node and whose graph holds the chain
   *     triples; {@link RDFList#empty()} if {@code items} is {@code null} or empty
   */
  RDFList createRDFList(List<RDFTerm> items);
}
