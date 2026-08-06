/**
 * CBOR-based RDF serialization for content addressing.
 *
 * <p>This package provides low-level CBOR serialization utilities for creating
 * deterministic, canonical representations of RDF datasets:</p>
 * <ul>
 *   <li>{@link io.kogn.rdf.cid.cbor.ContentAddressableRdfSerializer} - serializes RDF to canonical CBOR</li>
 *   <li>{@link io.kogn.rdf.cid.cbor.RdfDatasetCanonicalizer} - canonicalizes RDF datasets for consistent hashing</li>
 * </ul>
 *
 * <p>These utilities ensure that semantically equivalent RDF graphs produce
 * identical byte representations, enabling reliable content-based addressing.</p>
 */
package io.kogn.rdf.cid.cbor;
