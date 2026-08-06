// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * Generator for content-addressed IRIs (Content Identifiers / CIDs).
 *
 * <p>Creates deterministic IRIs based on the content of RDF graphs. Identical
 * RDF content will always produce the same IRI, enabling content deduplication
 * and verification.</p>
 *
 * <p>This is useful for:</p>
 * <ul>
 *   <li>Federation with content deduplication</li>
 *   <li>Verifiable data integrity</li>
 *   <li>Content-addressed storage systems</li>
 * </ul>
 *
 * <p>The generated IRIs typically follow the format: {@code urn:cid:<hash>}</p>
 *
 * @see ReadableGraph
 * @see IRI
 */
public interface ContentAddressedIriGenerator {

  /**
   * Generates a content-addressed IRI for the given RDF graph.
   *
   * <p>The method analyzes the graph content and produces a deterministic IRI
   * based on a cryptographic hash of the normalized RDF representation.
   * The same graph content will always produce the same IRI.</p>
   *
   * @param graph the RDF graph to generate an IRI for
   * @return a content-addressed IRI (e.g., {@code urn:cid:abc123...})
   * @throws IllegalArgumentException if the graph is null or empty
   * @throws IllegalStateException if content addressing fails
   */
  IRI generateIri(ReadableGraph graph);
}
