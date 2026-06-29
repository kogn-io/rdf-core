// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

import java.util.stream.Stream;

/**
 * Read-only view of an RDF Graph - a set of RDF triples.
 *
 * <p>A readable graph provides query access to triples without
 * allowing modification. Use {@link Graph} for mutable operations.</p>
 *
 * @see Graph
 */
public interface ReadableGraph {

  /**
   * Checks if the graph contains a specific triple.
   *
   * @param triple the triple to check
   * @return true if the triple exists in the graph
   */
  boolean contains(Triple triple);

  /**
   * Returns the number of triples in the graph.
   *
   * @return the size of the graph
   */
  long size();

  /**
   * Returns a stream of all triples in the graph.
   *
   * @return stream of triples
   */
  Stream<Triple> stream();

  /**
   * Returns a stream of triples matching the given pattern.
   *
   * <p>Use {@code null} as wildcard for any component. For example:</p>
   * <ul>
   *   <li>{@code stream(subject, null, null)} - all triples with given subject</li>
   *   <li>{@code stream(null, predicate, null)} - all triples with given predicate</li>
   *   <li>{@code stream(subject, predicate, null)} - all triples with given subject and predicate</li>
   * </ul>
   *
   * @param subject the subject to match, or null for any subject
   * @param predicate the predicate to match, or null for any predicate
   * @param object the object to match, or null for any object
   * @return stream of matching triples
   */
  Stream<Triple> stream(BlankNodeOrIRI subject, IRI predicate, RDFTerm object);

  boolean isEmpty();
}
