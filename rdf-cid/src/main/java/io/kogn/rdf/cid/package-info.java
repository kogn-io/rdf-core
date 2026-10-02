/**
 * Content-addressed IRI generation for RDF graphs.
 *
 * <p>This package derives content identifiers (CIDs) of the form {@code urn:cid:<hash>} from
 * an RDF graph: the graph is canonicalized with URDNA2015, serialized into a sorted,
 * length-prefixed S-expression form — blank nodes under deterministic skolem names — and
 * hashed with SHA3-256.</p>
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
 * <p>One IRI is the exception: the <em>base IRI</em> of the described resource. It goes into
 * the digest as a fixed self placeholder in every position — subject, predicate, object and a
 * literal's datatype — and its fragment IRIs ({@code <base#part>}) by their fragment alone, the
 * part after the first {@code #}. Two graphs describing the same thing under
 * different base IRIs are therefore the same content — which is what lets a resource be
 * renamed to its own identifier and still verify against it. Every other IRI in the data is
 * part of the content. The placeholder follows the fragment molecules of
 * <a href="https://openengiadina.codeberg.page/rdf-cbor/">RDF/CBOR</a>.</p>
 *
 * <h2>Architecture role</h2>
 * <p>This is a backend-neutral port: {@link io.kogn.rdf.cid.ContentAddressedIriGenerator}
 * defines the contract, {@link io.kogn.rdf.cid.ContentAddressedIriGeneratorSexpr} is its
 * only implementation, both live in this leaf module.</p>
 *
 * @see io.kogn.rdf.cid.ContentAddressedIriGenerator
 */
package io.kogn.rdf.cid;
