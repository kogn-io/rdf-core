/**
 * Content-addressed IRI generation using CBOR-based serialization.
 *
 * <p>This package provides an implementation of content-addressed identifiers (CIDs)
 * for RDF graphs using CBOR (Compact Binary Object Representation) for deterministic
 * serialization.</p>
 *
 * <h2>Why content addressing</h2>
 * <p>Content addressing creates identifiers based on the actual content rather than
 * location or random assignment. This enables:</p>
 * <ul>
 *   <li><strong>Federation:</strong> When the same resource is published on different
 *       instances, content-addressed identifiers let peers recognize identical content
 *       regardless of the originating server.</li>
 *   <li><strong>Import deduplication:</strong> Re-importing the same external dataset
 *       produces the same CIDs, so duplicate imports are detected without additional
 *       bookkeeping.</li>
 *   <li><strong>Environment independence:</strong> the CID depends only on the graph's
 *       content, not on the URIs assigned to it in a particular environment.</li>
 * </ul>
 *
 * <h2>Architecture role</h2>
 * <p>This is a backend-neutral port: {@link io.kogn.rdf.cid.ContentAddressedIriGenerator}
 * defines the contract, {@link io.kogn.rdf.cid.ContentAddressedIriGeneratorCbor} is its
 * only implementation, both live in this leaf module.</p>
 *
 * @see io.kogn.rdf.cid.ContentAddressedIriGenerator
 */
package io.kogn.rdf.cid;
