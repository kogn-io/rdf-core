// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import io.kogn.rdf.cid.sexpr.ContentAddressableRdfSerializer;
import io.kogn.rdf.cid.sexpr.ContentAddressableRdfSerializer.SingleContentAddressableResult;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.Triple;
import lombok.extern.slf4j.Slf4j;

/**
 * Content-addressed IRI generator over a canonicalized, length-prefixed S-expression form.
 *
 * <p>The graph is canonicalized with URDNA2015, serialized into a sorted S-expression of
 * length-prefixed fields — blank nodes under deterministic skolem names — and hashed with
 * Blake2b-256. Identical RDF graphs — regardless of blank node labels or triple order —
 * therefore always produce the same identifier.</p>
 */
@Slf4j
public class ContentAddressedIriGeneratorSexpr implements ContentAddressedIriGenerator {

  private final RDF rdf;
  private final ContentAddressableRdfSerializer contentAddressableRdfSerializer;

  /**
   * Creates a generator.
   *
   * @param rdf the term factory used to create the resulting IRI
   * @param contentAddressableRdfSerializer the serializer that derives the content-addressed URN
   */
  public ContentAddressedIriGeneratorSexpr(RDF rdf, ContentAddressableRdfSerializer contentAddressableRdfSerializer) {
    this.rdf = Objects.requireNonNull(rdf, "rdf must not be null");
    this.contentAddressableRdfSerializer = Objects.requireNonNull(contentAddressableRdfSerializer,
        "contentAddressableRdfSerializer must not be null");
  }

  @Override
  public IRI generateIri(ReadableGraph graph) {
    if (graph == null || graph.size() == 0) {
      throw new IllegalArgumentException("Graph cannot be null or empty");
    }

    List<Triple> triples = graph.stream().collect(Collectors.toList());

    SingleContentAddressableResult result;
    try {
      result = contentAddressableRdfSerializer.serializeWithUrn(triples);
    } catch (IllegalArgumentException | ContentAddressingException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ContentAddressingException("Failed to generate content-addressed IRI", e);
    }

    String iriString = result.urn().getIRIString();

    log.debug("Generated content-addressed IRI: {} for graph with {} triples", iriString, graph.size());

    return rdf.createIRI(iriString);
  }
}
