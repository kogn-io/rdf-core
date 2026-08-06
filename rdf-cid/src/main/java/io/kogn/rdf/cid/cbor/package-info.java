/**
 * Canonical RDF serialization for content addressing.
 *
 * <p>This package provides the low-level utilities that turn an RDF graph into the
 * deterministic byte form the content identifier is hashed from:</p>
 * <ul>
 *   <li>{@link io.kogn.rdf.cid.cbor.ContentAddressableRdfSerializer} - serializes RDF into a
 *       sorted, length-prefixed S-expression form and derives the {@code urn:cid:} URN</li>
 *   <li>{@link io.kogn.rdf.cid.cbor.RdfDatasetCanonicalizer} - canonicalizes RDF datasets for consistent hashing</li>
 * </ul>
 *
 * <p>These utilities ensure that semantically equivalent RDF graphs produce
 * identical byte representations, enabling reliable content-based addressing.</p>
 *
 * <p><strong>The package name is inaccurate.</strong> Nothing here serializes CBOR — the
 * hashed form is a length-prefixed S-expression. The name is inherited from the origin of
 * this code and kept because renaming a published package is a breaking change for no
 * functional gain; ADR-0014 records the trade-off.</p>
 */
package io.kogn.rdf.cid.cbor;
