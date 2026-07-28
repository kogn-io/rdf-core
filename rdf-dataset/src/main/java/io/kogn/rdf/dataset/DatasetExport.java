// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

import java.io.OutputStream;

import io.kogn.rdf.terms.IRI;

/**
 * Serialization port — writes a dataset, or one of its named graphs, to a byte stream.
 *
 * <p>The counterpart to {@link GraphStore#export(IRI)}, which despite its name serializes
 * nothing: it hands back an in-memory {@link io.kogn.rdf.terms.ReadableGraph} snapshot of one
 * named graph. This port produces the actual document — Turtle, TriG, N-Triples or N-Quads
 * (see {@link RdfFormat}) — that a dump, a backup or an HTTP response body is made of.</p>
 *
 * <p><strong>Reading, not a unit of work.</strong> Export never mutates the dataset, and like
 * {@link SparqlQuery} it runs outside of any caller-visible transaction: each call is a
 * self-contained read against the current committed state of the store. It is deliberately not
 * part of {@link DatasetTx}, unlike the three ports that interface composes — a dump streams
 * for as long as the caller's sink takes to swallow it, and holding a transaction open for that
 * would make an unbounded, caller-paced I/O operation part of an atomic unit of work whose
 * conflict surface is the entire store. A caller who needs a serialized snapshot that is atomic
 * with its other work can take one inside a transaction with {@link DatasetTx#export(IRI)} and
 * serialize the returned graph afterwards. See ADR-0013.</p>
 *
 * <p><strong>The stream belongs to the caller.</strong> Implementations write to it and flush
 * what they wrote before returning, but never close it — closing (and opening) is the caller's
 * business, since only the caller knows whether the stream is a file, a socket or a response
 * body that continues after the RDF. A failed export may leave a partial document behind: the
 * bytes already written are not taken back, so a caller retrying must discard them.</p>
 */
public interface DatasetExport {

  /**
   * Serializes the entire dataset — the union of all named graphs, every statement tagged
   * with the graph it belongs to — to {@code out}.
   *
   * <p>The format must be {@linkplain RdfFormat#isQuadCapable() quad-capable}: a
   * triple-only format has nowhere to put the graph name, so it would flatten distinct
   * named graphs into one document and silently lose which statement came from where.
   * Rejecting it is therefore a guard against silent data loss, not a formality — use
   * {@link #export(OutputStream, RdfFormat, IRI)} if a single graph is what you want in
   * Turtle or N-Triples.</p>
   *
   * <p>An empty dataset yields a document with no statements; depending on the format
   * that document may still carry a preamble such as namespace declarations.</p>
   *
   * @param out the stream to write to; must not be {@code null}. Written to and flushed,
   *     never closed
   * @param format the serialization format; must not be {@code null} and must be
   *     quad-capable
   * @throws NullPointerException if {@code out} or {@code format} is {@code null}
   * @throws IllegalArgumentException if {@code format} is not
   *     {@linkplain RdfFormat#isQuadCapable() quad-capable}
   * @throws RdfExportException if writing to {@code out} fails
   */
  void export(OutputStream out, RdfFormat format);

  /**
   * Serializes a single named graph to {@code out}.
   *
   * <p>Any {@link RdfFormat} is valid here, quad-capable or not: with only one graph in
   * play there is no graph name to lose. A quad-capable format still writes the graph name
   * it was given, so a TriG or N-Quads document produced this way round-trips back into the
   * same named graph.</p>
   *
   * <p>Writes a document with no statements if the named graph does not exist or is empty,
   * mirroring {@link GraphStore#export(IRI)}, which returns an empty graph in the same
   * case — a dataset does not distinguish an absent named graph from an empty one.</p>
   *
   * @param out the stream to write to; must not be {@code null}. Written to and flushed,
   *     never closed
   * @param format the serialization format; must not be {@code null}
   * @param namedGraph IRI identifying the named graph to serialize; must not be
   *     {@code null}
   * @throws NullPointerException if {@code out}, {@code format} or {@code namedGraph} is
   *     {@code null}
   * @throws RdfExportException if writing to {@code out} fails
   */
  void export(OutputStream out, RdfFormat format, IRI namedGraph);
}
