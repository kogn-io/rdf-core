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
 * <p>The generated IRIs follow the format {@code urn:cid:<hash>}, the hash being an
 * unpadded, lower-case Base32 digest.</p>
 *
 * <h2>What the identifier is derived from</h2>
 *
 * <p>Every term of every triple goes into the hash in full: an IRI by its IRI string, a
 * literal by its lexical form, its datatype <em>and</em> its language tag, a blank node by
 * its structural position rather than its label. Two graphs therefore share an identifier
 * if and only if they hold the same triples up to blank node labelling and triple order.</p>
 *
 * <p>Read the other way round: the identifier is <strong>not</strong> independent of the
 * IRIs the data uses. Two graphs that describe the same thing under different subject IRIs
 * are different content and get different identifiers.</p>
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
   * <p><strong>Preconditions.</strong> The graph must describe exactly one resource: it holds
   * triples of <strong>exactly one
   * IRI subject</strong>, plus any blank node triples reachable from it. A graph with no IRI
   * subject, with several of them, or with triples no IRI subject reaches is rejected rather
   * than silently reduced — an identifier that ignores part of its input would let two
   * different graphs share one.</p>
   *
   * <p><strong>Not every graph meeting those preconditions gets an identifier.</strong> The
   * shipped canonicalizer caps the permutations it will try while telling apart blank nodes
   * it cannot yet distinguish; a graph with enough symmetric blank node structure — for
   * instance a densely interconnected cluster where every edge carries the same predicate —
   * can exceed that cap and come back with no identifier at all, as a
   * {@link CanonicalizationResourceLimitExceededException}. This is a property of the
   * canonicalizer this module ships, not of URDNA2015 itself, so the exact set of
   * addressable graphs is implementation-defined and may change with the canonicalizer.</p>
   *
   * @param graph the RDF graph to generate an IRI for
   * @return a content-addressed IRI (e.g., {@code urn:cid:abc123...})
   * @throws IllegalArgumentException if the graph is null or empty, does not hold exactly one
   *         IRI subject, or holds triples not reachable from that subject
   * @throws CanonicalizationResourceLimitExceededException if the graph satisfies those
   *         preconditions but its blank node structure exceeds the canonicalizer's
   *         permutation limit
   * @throws ContentAddressingException if the graph satisfies those preconditions but the
   *         identifier cannot otherwise be derived
   */
  IRI generateIri(ReadableGraph graph);
}
