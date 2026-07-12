/**
 * Technology- and library-free Commons-RDF-oriented data model.
 *
 * <p>This module provides a pure Java representation of the core RDF abstract syntax,
 * modelled after the
 * <a href="https://commons.apache.org/proper/commons-rdf/apidocs/org/apache/commons/rdf/api/package-summary.html">Apache Commons RDF API</a>
 * but without any dependency on that library or any other framework.</p>
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
