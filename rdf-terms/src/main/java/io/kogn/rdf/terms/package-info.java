/**
 * Technology- and library-free Commons-RDF-oriented data model.
 *
 * <p>This module provides a pure Java representation of the core RDF abstract syntax,
 * modelled after the
 * <a href="https://commons.apache.org/proper/commons-rdf/apidocs/org/apache/commons/rdf/api/package-summary.html">Apache Commons RDF API</a>
 * but without any dependency on that library or any other framework.</p>
 *
 * <h2>RDF 1.1</h2>
 * <p>The data model is <a href="https://www.w3.org/TR/rdf11-concepts/">RDF 1.1</a>. The RDF 1.2
 * extensions have no representation here: triple terms ({@code <<( s p o )>>}, including the
 * ones the reification syntax {@code << s p o >>} produces) and directional language strings
 * ({@code "…"@en--ltr}, {@code rdf:dirLangString}). A backend store may still accept such
 * content. Keeping it out of the store is the caller's responsibility, not something the ports
 * check on write; each implementation states what it enforces when it meets some. The factories
 * of this module reject {@code rdf:dirLangString} as a plain datatype, see
 * {@link io.kogn.rdf.terms.RDF#createLiteral(String, io.kogn.rdf.terms.IRI)}.</p>
 *
 * <h2>Term types</h2>
 * <ul>
 *   <li>{@link io.kogn.rdf.terms.RDFTerm} — common supertype of all RDF terms</li>
 *   <li>{@link io.kogn.rdf.terms.IRI} — Internationalized Resource Identifier</li>
 *   <li>{@link io.kogn.rdf.terms.BlankNode} — anonymous node</li>
 *   <li>{@link io.kogn.rdf.terms.Literal} — literal value with optional language tag or datatype</li>
 * </ul>
 *
 * <h2>Graph model</h2>
 * <ul>
 *   <li>{@link io.kogn.rdf.terms.Triple} — subject–predicate–object statement</li>
 *   <li>{@link io.kogn.rdf.terms.ReadableGraph} — read-only view over a set of triples</li>
 *   <li>{@link io.kogn.rdf.terms.Graph} — mutable triple container</li>
 *   <li>{@link io.kogn.rdf.terms.NamedGraph} — graph with an associated IRI</li>
 *   <li>{@link io.kogn.rdf.terms.RDFList} — RDF list utility</li>
 * </ul>
 *
 * <h2>Factory</h2>
 * <ul>
 *   <li>{@link io.kogn.rdf.terms.RDF} — factory interface for creating terms and graphs</li>
 * </ul>
 *
 * <h2>Standard vocabularies</h2>
 * <p>Namespace constants for widely-used vocabularies are provided in the
 * {@code io.kogn.rdf.terms.vocab} sub-package (RDF, RDFS, XSD, DCT, schema.org, …).</p>
 *
 * @see io.kogn.rdf.terms.RDF
 * @see io.kogn.rdf.terms.RDFTerm
 */
package io.kogn.rdf.terms;
