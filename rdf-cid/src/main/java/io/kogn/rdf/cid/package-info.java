/**
 * Content-addressed IRI generation for RDF graphs.
 *
 * <p>This package derives content identifiers (CIDs) of the form
 * {@code ni:///sha3-256;<digest>} (RFC 6920 named information) for the one resource an RDF
 * graph describes, following the procedure ni-rdf/1 ({@code docs/spec/ni-rdf/v1.md} in the
 * kognio-rdf repository, with test vectors): the blank nodes are labelled with RDFC-1.0, the
 * triples serialized into a sorted, length-prefixed S-expression form opened by the procedure
 * tag {@code ni-rdf/1}, and that form hashed with SHA3-256.</p>
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
 * {@code "Bank"@en} and {@code "Bank"@de}, and {@code "01"^^xsd:integer} and
 * {@code "1"^^xsd:integer}: lexical forms are not normalized. Only the case of a language tag
 * is, since RDF 1.1 compares language tags case-insensitively.</p>
 *
 * <p>One IRI is the exception: the <em>base IRI</em> of the described resource, which the
 * caller passes in rather than having it inferred from the triples. It goes into
 * the digest as a fixed self placeholder in every position — subject, predicate, object and a
 * literal's datatype — and its fragment IRIs ({@code <base#part>}) by their fragment alone, the
 * part after the first {@code #}. Two graphs describing the same thing under
 * different base IRIs are therefore the same content — which is what lets a resource be
 * renamed to its own identifier and still verify against it. Every other IRI in the data is
 * part of the content. The placeholder follows the fragment molecules of
 * <a href="https://openengiadina.codeberg.page/rdf-cbor/">RDF/CBOR</a>.</p>
 *
 * <h2>Input and failures</h2>
 * <p>The input is assumed to be valid RDF 1.1; ni-rdf/1 defines no result for anything else.
 * A foreign term that visibly breaks the term contract (a {@code null} term or accessor value)
 * is rejected with {@code [UNSUPPORTED_TERM]}. Other exceptions of the supplied graph, its terms
 * and the term factory pass through untranslated. Only the failures of the specification's §5
 * carry a leading {@code [CODE]} in their message; an {@link IllegalArgumentException} without
 * it is a {@code null} argument or a rejection by the graph or the factory. A failure of the
 * canonicalization itself is a {@link io.kogn.rdf.cid.ContentAddressingException}.</p>
 *
 * <h2>Architecture role</h2>
 * <p>This is a backend-neutral port: {@link io.kogn.rdf.cid.ContentAddressedIriGenerator}
 * defines the contract, {@link io.kogn.rdf.cid.ContentAddressedIriGeneratorSexpr} is its
 * only implementation, both live in this leaf module.</p>
 *
 * @see io.kogn.rdf.cid.ContentAddressedIriGenerator
 */
package io.kogn.rdf.cid;
