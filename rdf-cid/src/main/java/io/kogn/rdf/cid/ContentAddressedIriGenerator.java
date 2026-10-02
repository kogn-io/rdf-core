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
 * <p>The graph describes one resource, named by a <em>base IRI</em> (an IRI without a
 * fragment). The base IRI itself stays out of the hash: wherever it occurs — subject,
 * predicate, object or a literal's datatype — it goes in as a fixed self placeholder, and each
 * of its fragment IRIs ({@code <base#part>}) as its fragment alone: everything after the first
 * {@code #}, further {@code #} characters included, possibly empty. Every other term goes in
 * in full: an IRI by its IRI string, a literal by its lexical form, its datatype <em>and</em>
 * its language tag, a blank node by its structural position rather than its label. Two graphs
 * therefore share an identifier if and only if they hold the same triples up to blank node
 * labelling, triple order and a renaming of the base IRI to one the graph does not otherwise
 * use.</p>
 *
 * <p>Keeping the base out is what lets a resource carry its own identifier: describe it under
 * any provisional IRI, generate the identifier, rename the base to it (and every
 * {@code <base#part>} to {@code <urn:cid:...#part>}), in every position including datatypes;
 * generating again over the renamed graph returns the same identifier, so a reader can verify
 * it. Two graphs that describe the same thing under different base IRIs are therefore the same
 * content. Every other IRI the data uses is part of the content.</p>
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
   * <p><strong>Preconditions.</strong> The graph must describe exactly one resource: all its
   * IRI subjects share <strong>exactly one base IRI</strong> — the IRI string before the first
   * {@code #} — and are that base and/or fragment IRIs of it; every other triple is a blank
   * node triple reachable from one of them. The base itself need not appear as a subject. A
   * graph with no IRI subject, with IRI subjects of several bases, or with triples no IRI
   * subject reaches is rejected rather than silently reduced — an identifier that ignores part
   * of its input would let two different graphs share one.</p>
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
   * @throws IllegalArgumentException if the graph is null or empty, holds no IRI subject or IRI
   *         subjects of more than one base IRI, or holds triples not reachable from an IRI
   *         subject
   * @throws CanonicalizationResourceLimitExceededException if the graph satisfies those
   *         preconditions but its blank node structure exceeds the canonicalizer's
   *         permutation limit
   * @throws ContentAddressingException if the graph satisfies those preconditions but the
   *         identifier cannot otherwise be derived
   */
  IRI generateIri(ReadableGraph graph);
}
