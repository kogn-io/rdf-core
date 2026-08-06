/**
 * Canonical RDF serialization for content addressing.
 *
 * <p>This package provides the low-level utilities that turn an RDF graph into the
 * deterministic byte form the content identifier is hashed from:</p>
 * <ul>
 *   <li>{@link io.kogn.rdf.cid.sexpr.ContentAddressableRdfSerializer} - serializes RDF into a
 *       sorted, length-prefixed S-expression form and derives the {@code urn:cid:} URN</li>
 *   <li>{@link io.kogn.rdf.cid.sexpr.RdfDatasetCanonicalizer} - canonicalizes RDF datasets for consistent hashing</li>
 * </ul>
 *
 * <p>These utilities ensure that semantically equivalent RDF graphs produce
 * identical byte representations, enabling reliable content-based addressing.</p>
 */
package io.kogn.rdf.cid.sexpr;
