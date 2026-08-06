/**
 * Content-addressed IRI generation for RDF graphs.
 *
 * <p>This package derives content identifiers (CIDs) of the form {@code urn:cid:<hash>} from
 * an RDF graph: the graph is canonicalized with URDNA2015, its blank nodes are skolemized
 * into deterministic IRIs, the result is serialized into a sorted, length-prefixed
 * S-expression form and hashed with Blake2b-256.</p>
 *
 * <h2>Why content addressing</h2>
 * <p>Content addressing creates identifiers based on the actual content rather than
 * location or random assignment. This enables:</p>
 * <ul>
 *   <li><strong>Federation:</strong> When the same graph is published on different
 *       instances, content-addressed identifiers let peers recognize identical content
 *       regardless of the originating server.</li>
 *   <li><strong>Import deduplication:</strong> Re-importing the same external dataset
 *       produces the same CIDs, so duplicate imports are detected without additional
 *       bookkeeping.</li>
 *   <li><strong>Integrity:</strong> the identifier is a digest over the whole graph, so it
 *       cannot still match after the data behind it changed.</li>
 * </ul>
 *
 * <h2>What "same content" means here</h2>
 * <p>Two graphs are the same content when they hold the same triples, up to blank node
 * labelling and triple order. Every term goes into the digest in full — an IRI by its IRI
 * string, a literal by lexical form, datatype <em>and</em> language tag — so
 * {@code "100"^^xsd:integer} and {@code "100"^^xsd:decimal} are different content, as are
 * {@code "Bank"@en} and {@code "Bank"@de}.</p>
 *
 * <p>The IRIs in the data are part of that: the identifier does <strong>not</strong> abstract
 * from the URIs a particular environment assigned. Two graphs describing the same thing under
 * different subject IRIs are different content and get different identifiers.</p>
 *
 * <h2>Architecture role</h2>
 * <p>This is a backend-neutral port: {@link io.kogn.rdf.cid.ContentAddressedIriGenerator}
 * defines the contract, {@link io.kogn.rdf.cid.ContentAddressedIriGeneratorCbor} is its
 * only implementation, both live in this leaf module.</p>
 *
 * @see io.kogn.rdf.cid.ContentAddressedIriGenerator
 */
package io.kogn.rdf.cid;
