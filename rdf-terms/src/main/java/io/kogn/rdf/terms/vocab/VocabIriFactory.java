// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms.vocab;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;

/**
 * Helper class for creating RDF vocabulary IRIs.
 *
 * <p>This utility class provides a centralized way to instantiate IRI constants
 * for RDF vocabularies by combining a namespace URI with a local name.</p>
 *
 * <p>Example usage:
 * <pre>{@code
 * IRI myTerm = VocabHelper.createIRI("http://example.org/vocab#", "MyTerm");
 * }</pre>
 */
class VocabIriFactory {

  private static RDF rdf = new SimpleRdf();

  /**
   * Creates an IRI by concatenating a namespace URI with a local name.
   *
   * @param namespace the namespace URI (e.g., "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
   * @param localName the local name of the term (e.g., "type")
   * @return an IRI representing the full URI
   */
  public static IRI createIRI(String namespace, String localName) {
    return rdf.createIRI(namespace + localName);
  }
}
