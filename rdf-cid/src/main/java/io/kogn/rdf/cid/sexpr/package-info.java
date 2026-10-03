/**
 * Canonical RDF serialization for content addressing.
 *
 * <p>This package provides the low-level utilities that turn an RDF graph into the
 * deterministic byte form the content identifier is hashed from:</p>
 * <ul>
 *   <li>{@link io.kogn.rdf.cid.sexpr.ContentAddressableRdfSerializer} - checks the
 *       preconditions of ni-rdf/1, serializes RDF into a sorted, length-prefixed S-expression
 *       form and derives the {@code ni:///sha3-256;} name</li>
 *   <li>{@link io.kogn.rdf.cid.sexpr.RdfDatasetCanonicalizer} - checks the deterministic
 *       resource limit and labels blank nodes with RDFC-1.0</li>
 * </ul>
 *
 * <p>These utilities ensure that semantically equivalent RDF graphs produce
 * identical byte representations, enabling reliable content-based addressing. The base IRI of
 * the described resource never reaches those bytes: it is written as a self placeholder, its
 * fragment IRIs as their fragments, so the representation is the same under any base IRI.</p>
 */
package io.kogn.rdf.cid.sexpr;
