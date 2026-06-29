// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

/**
 * Represents an RDF list (collection) with a head blank node and the graph containing list triples.
 *
 * <p>RDF lists use rdf:first, rdf:rest, and rdf:nil to represent ordered collections.
 * This record encapsulates the head node and the graph containing the list structure.</p>
 *
 * @param head the head blank node of the RDF list
 * @param graph the graph containing the RDF list triples (rdf:first, rdf:rest, rdf:nil)
 */
public record RDFList(BlankNode head, ReadableGraph graph) {

  /**
   * Creates an empty RDF list.
   *
   * @return an empty RDF list with null head and null graph
   */
  public static RDFList empty() {
    return new RDFList(null, null);
  }

  /**
   * Checks if this list has any items.
   *
   * @return true if the list has items, false otherwise
   */
  public boolean hasItems() {
    return head != null && graph != null && graph.size() > 0;
  }
}
