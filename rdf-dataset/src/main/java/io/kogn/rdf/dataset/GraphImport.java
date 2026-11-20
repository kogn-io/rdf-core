// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

import java.io.InputStream;

import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;

/**
 * Parsing port — reads an RDF document from a byte stream into a {@link Graph} of the port data
 * model.
 *
 * <p>The typical use is loading a bundled document — SHACL shapes, an ontology, seed data — from
 * the classpath or a file without depending on a backend's parser. The result is an in-memory
 * graph the caller owns; nothing is written to a store. To put it into one, hand it to
 * {@link GraphStore#add(IRI, io.kogn.rdf.terms.ReadableGraph)}.</p>
 *
 * <p><strong>Turtle only, for now.</strong> The port reads <a href="https://www.w3.org/TR/turtle/">
 * Turtle</a> and nothing else; a format is therefore not a parameter but part of the method name.
 * Further formats are expected to be added as further operations rather than by changing this
 * one: a triple-only format such as N-Triples fits the same shape, while a quad format such as
 * TriG or N-Quads, which {@link DatasetExport} also writes, carries graph names a single
 * {@link Graph} has no place for and needs a different result.</p>
 *
 * <p><strong>The stream belongs to the caller.</strong> Implementations read it up to the end of
 * the document, or up to the point where the import fails, but never close it — opening and
 * closing it is the caller's business, as for {@link DatasetExport}. A typical call therefore
 * sits inside a try-with-resources block of the caller's. The bytes are decoded as UTF-8, the
 * only encoding Turtle allows; a byte sequence that is not UTF-8 fails the import rather than
 * being replaced, and a leading byte order mark is skipped.</p>
 *
 * <p><strong>All or nothing.</strong> A call either returns the graph of the complete document or
 * throws; the triples read before a failure are discarded, never handed out as a partial
 * graph.</p>
 *
 * <p><strong>Literals as written.</strong> A literal keeps its lexical form and datatype; values
 * are neither validated nor normalized, so an ill-typed literal such as
 * {@code "abc"^^xsd:integer}, which is valid RDF, arrives unchanged. Language tags arrive
 * lower-cased, as everywhere in the data model.</p>
 *
 * <p><strong>The RDF 1.1 data model.</strong> The port data model is RDF 1.1 (see
 * {@link io.kogn.rdf.terms}). A document holding content it cannot represent is rejected rather
 * than altered to fit:</p>
 *
 * <ul>
 *   <li>an RDF 1.2 triple term — written {@code <<( s p o )>>}, or produced by the reification
 *       syntax {@code << s p o >>} or an annotation {@code {| … |}};</li>
 *   <li>an RDF 1.2 directional language string — {@code "x"@en--ltr}, or a literal of datatype
 *       {@code rdf:dirLangString}. This rejection may be loosened once the data model's treatment
 *       of such literals is settled; no caller relying on it today can be broken by that;</li>
 *   <li>a literal of datatype {@code rdf:langString} without a language tag, which is not an RDF
 *       literal at all.</li>
 * </ul>
 *
 * <p>The read paths of this library reject a triple term too, but report it as an
 * {@link IllegalStateException}, since there it is the state of the store. Here the document is
 * the input, and the rejection is an {@link RDFImportException} like that of any other document
 * that cannot be read.</p>
 */
public interface GraphImport {

  /**
   * Reads a Turtle document into a new graph.
   *
   * <p><strong>Base IRI.</strong> Relative IRI references in the document are resolved against
   * {@code base} (RFC 3986, section 5), unless the document declares its own base with
   * {@code @base} or {@code BASE}, which takes precedence from that point on. {@code base} must
   * be an absolute IRI, even for a document that holds no relative reference, so the outcome
   * never depends on whether one happens to appear; a fragment in it is allowed and plays no part
   * in the resolution. A document that holds no relative reference is unaffected by the value,
   * so the IRI the document was loaded from is a sensible choice.</p>
   *
   * <p><strong>Blank nodes.</strong> Within one document a blank-node label denotes one blank
   * node, and every {@code []} or property list a node of its own. Across calls blank nodes are
   * never shared: the same label in two documents, or the same document read twice, yields
   * distinct blank nodes, so two imported graphs can be added to one named graph without their
   * blank nodes merging. The labels of the document are not preserved.</p>
   *
   * <p>A triple stated more than once is held once, as a graph is a set of triples. A document
   * without statements yields an empty graph.</p>
   *
   * @param in the stream to read the document from; must not be {@code null}. Read up to the end
   *     of the document or the failure, never closed
   * @param base the absolute IRI to resolve relative IRI references against; must not be
   *     {@code null}
   * @return a new, modifiable graph holding the document's triples, owned by the caller
   * @throws NullPointerException if {@code in} or {@code base} is {@code null}
   * @throws IllegalArgumentException if {@code base} is not an absolute IRI
   * @throws RDFImportException if the document is not valid Turtle (including a relative reference
   *     that does not resolve to an absolute IRI, and bytes that are not UTF-8), holds content the
   *     data model cannot represent (an RDF 1.2 triple term, a directional language string, an
   *     {@code rdf:langString} literal without a language tag), or reading from {@code in} fails
   */
  Graph readTurtle(InputStream in, IRI base);
}
