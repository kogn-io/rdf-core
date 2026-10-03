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
 * <p>The generated IRIs are RFC 6920 named information names in the canonical form
 * {@code ni:///sha3-256;<digest>}: no authority, no query, the 32-byte SHA3-256 digest in
 * base64url without padding (43 characters). The derivation is the procedure ni-rdf/1,
 * specified byte for byte in {@code docs/spec/ni-rdf/v1.md} of the kognio-rdf repository, with
 * test vectors next to it; two conforming implementations derive the same name for the same
 * content.</p>
 *
 * <h2>What the identifier is derived from</h2>
 *
 * <p>The graph describes one resource, named by a <em>base IRI</em> (an IRI without a
 * {@code #}) that the caller passes alongside it: the identifier is that of a resource, not of
 * an arbitrary graph, and which resource is meant is the caller's to say, never inferred from
 * the triples. The base IRI itself stays out of the hash: wherever it occurs — subject,
 * predicate, object or a literal's datatype — it goes in as a fixed self placeholder, and each
 * of its fragment IRIs ({@code <base#part>}) as its fragment alone: everything after the first
 * {@code #}, further {@code #} characters included, possibly empty. Every other term goes in
 * in full: an IRI by its IRI string, a literal by its lexical form, its datatype <em>and</em>
 * its language tag (lower-cased, as RDF 1.1 compares language tags case-insensitively), a
 * blank node by its RDFC-1.0 canonical label rather than its own. Two graphs therefore share an
 * identifier if and only if they hold the same triples up to blank node labelling, triple
 * order, language tag case and a renaming of the base IRI to one the graph does not otherwise
 * use.</p>
 *
 * <p>Keeping the base out is what lets a resource carry its own identifier: describe it under
 * any provisional IRI, generate the identifier, rename the base to it (and every
 * {@code <base#part>} to {@code <ni:///sha3-256;...#part>}), in every position including
 * datatypes; generating again over the renamed graph, with the identifier as base, returns the
 * same identifier, so a reader can verify it. Two graphs that describe the same thing under
 * different base IRIs are therefore the same content. Every other IRI the data uses is part of
 * the content.</p>
 *
 * @see ReadableGraph
 * @see IRI
 */
public interface ContentAddressedIriGenerator {

  /**
   * Generates a content-addressed IRI for the resource {@code base} that the given graph
   * describes.
   *
   * <p>The method analyzes the graph content and produces a deterministic IRI
   * based on a cryptographic hash of the normalized RDF representation.
   * The same graph content will always produce the same IRI.</p>
   *
   * <p><strong>Preconditions.</strong> The graph must describe exactly the resource
   * {@code base}: every IRI subject is {@code base} itself or a fragment IRI of it
   * ({@code <base#part>}, the IRI string before the first {@code #} equal to {@code base}),
   * and every other triple is a blank node triple reachable from one of them. The base itself
   * need not appear as a subject. A graph with no such subject, with an IRI subject of another
   * resource, or with triples no IRI subject reaches is rejected rather than silently reduced —
   * an identifier that ignores part of its input would let two different graphs share one.
   * Neither the base nor any IRI of the graph may have the IRI ni-rdf/1 reserves for the
   * placeholder ({@code urn:uuid:171650ec-eda4-47fd-9053-6a34696171c1}) as its base, and the
   * graph may hold RDF 1.1 terms only. These conditions depend on the triples, so no parameter
   * type can carry them; they are checked on every call, in the order ni-rdf/1 §3 lists them,
   * and a failure message starts with the specification's failure code in brackets
   * ({@code [INVALID_BASE]}, {@code [RESERVED_IRI]}, {@code [EMPTY_GRAPH]},
   * {@code [UNSUPPORTED_TERM]}, {@code [FOREIGN_SUBJECT]}, {@code [NO_ROOT]},
   * {@code [UNREACHABLE]}), the first failing one reported.</p>
   *
   * <p><strong>Not every graph meeting those preconditions gets an identifier.</strong>
   * RDFC-1.0 runs in factorial time on blank nodes it cannot tell apart by their own triples
   * and that have several mutually indistinguishable neighbours. ni-rdf/1 §4.3 estimates that
   * work as a cost bound before canonicalizing, and a graph whose bound exceeds 10 000 000
   * (for instance 7 blank nodes all linked to each other by one predicate) gets no
   * identifier, as a {@link CanonicalizationResourceLimitExceededException}. The limit
   * depends on the graph alone, not on the speed or the budget of the canonicalizer, so every
   * conforming implementation accepts and rejects the same graphs. The bound is deliberately
   * conservative and also rejects some graphs that would be cheap. RDF lists, nested anonymous
   * structures and repeated identical anonymous nodes stay far below it.</p>
   *
   * @param base the base IRI of the resource the graph describes; carries no {@code #}
   * @param graph the RDF graph describing that resource
   * @return a content-addressed IRI of the form {@code ni:///sha3-256;<43 base64url characters>}
   * @throws IllegalArgumentException if {@code base} or the graph is null, or a precondition
   *         does not hold: the base carries a {@code #} or is the reserved IRI, the graph is
   *         empty, holds a term other than an RDF 1.1 IRI, blank node or literal, uses an IRI
   *         whose base is the reserved IRI, holds an IRI subject that is neither {@code base}
   *         nor a fragment IRI of it, holds no IRI subject, or holds triples not reachable from
   *         an IRI subject
   * @throws CanonicalizationResourceLimitExceededException if the graph satisfies those
   *         preconditions but its cost bound exceeds the resource limit of ni-rdf/1
   * @throws ContentAddressingException if the graph satisfies those preconditions but the
   *         identifier cannot be derived: for now also when the graph has blank nodes and mixes
   *         code points from U+10000 up with ones from U+E000 to U+FFFF, which the pinned
   *         canonicalizer would order wrongly (titanium-rdf-canon#65)
   */
  IRI generateIri(IRI base, ReadableGraph graph);
}
