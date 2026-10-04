// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import java.util.List;
import java.util.Objects;

import io.kogn.rdf.cid.sexpr.ContentAddressableRdfSerializer;
import io.kogn.rdf.cid.sexpr.ContentAddressableRdfSerializer.ContentAddressableResult;
import io.kogn.rdf.cid.sexpr.RdfDatasetCanonicalizer;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Content-addressed IRI generator implementing the specification ni-rdf/1
 * ({@code docs/spec/ni-rdf/v1.md} in the kognio-rdf repository).
 *
 * <p>The graph's language tags are lower-cased, the base IRI and its fragment IRIs are mapped
 * onto a reserved placeholder IRI, the blank nodes are labelled with RDFC-1.0 (after a
 * deterministic resource limit, see {@link CanonicalizationResourceLimitExceededException}),
 * and the triples are serialized into a sorted S-expression of length-prefixed fields, hashed
 * with SHA3-256 and returned as an RFC 6920 name {@code ni:///sha3-256;<base64url>}. Identical
 * RDF graphs — regardless of blank node labels, triple order, language tag case or the base
 * IRI — therefore always produce the same identifier. The work is done by
 * {@link ContentAddressableRdfSerializer}.</p>
 */
public class ContentAddressedIriGeneratorSexpr implements ContentAddressedIriGenerator {

  private final RDF rdf;
  private final ContentAddressableRdfSerializer contentAddressableRdfSerializer;

  /** Creates a generator using {@link SimpleRdf} to create the resulting IRI. */
  public ContentAddressedIriGeneratorSexpr() {
    this(new SimpleRdf());
  }

  /**
   * Creates a generator.
   *
   * @param rdf the term factory used to create the resulting IRI
   */
  public ContentAddressedIriGeneratorSexpr(RDF rdf) {
    this(rdf, new ContentAddressableRdfSerializer(new RdfDatasetCanonicalizer(), rdf));
  }

  /**
   * Creates a generator.
   *
   * @param rdf the term factory used to create the resulting IRI
   * @param contentAddressableRdfSerializer the serializer that derives the name
   */
  public ContentAddressedIriGeneratorSexpr(RDF rdf, ContentAddressableRdfSerializer contentAddressableRdfSerializer) {
    this.rdf = Objects.requireNonNull(rdf, "rdf must not be null");
    this.contentAddressableRdfSerializer = Objects.requireNonNull(contentAddressableRdfSerializer,
        "contentAddressableRdfSerializer must not be null");
  }

  @Override
  public IRI generateIri(IRI base, ReadableGraph graph) {
    if (graph == null) {
      throw new IllegalArgumentException("Graph cannot be null");
    }

    List<Triple> triples = graph.stream().toList();

    ContentAddressableResult result = contentAddressableRdfSerializer.serializeWithIri(base, triples);

    return rdf.createIRI(result.iri().getIRIString());
  }
}
