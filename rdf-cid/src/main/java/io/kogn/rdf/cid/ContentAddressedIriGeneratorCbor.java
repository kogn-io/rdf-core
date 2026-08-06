// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import java.util.List;
import java.util.stream.Collectors;

import io.kogn.rdf.cid.cbor.ContentAddressableRdfSerializer;
import io.kogn.rdf.cid.cbor.ContentAddressableRdfSerializer.ContentAddressableResult;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.Triple;
import lombok.extern.slf4j.Slf4j;

/**
 * Content-addressed IRI generator over a canonicalized, length-prefixed S-expression form.
 *
 * <p>The graph is canonicalized with URDNA2015, its blank nodes are skolemized into
 * deterministic IRIs, the result is serialized into a sorted S-expression of length-prefixed
 * fields and hashed with Blake2b-256. Identical RDF graphs — regardless of blank node labels
 * or triple order — therefore always produce the same identifier.</p>
 *
 * <p>The {@code Cbor} in the name and the {@code io.kogn.rdf.cid.cbor} package below it are
 * inherited from the origin of this code and are inaccurate: nothing here serializes CBOR.
 * The names are kept because they are a published API surface; ADR-0014 records the
 * trade-off.</p>
 */
@Slf4j
public class ContentAddressedIriGeneratorCbor implements ContentAddressedIriGenerator {

  private final RDF rdf;
  private final ContentAddressableRdfSerializer contentAddressableRdfSerializer;

  /**
   * Creates a generator.
   *
   * @param rdf the term factory used to create the resulting IRI
   * @param contentAddressableRdfSerializer the serializer that derives the content-addressed URN
   */
  public ContentAddressedIriGeneratorCbor(RDF rdf, ContentAddressableRdfSerializer contentAddressableRdfSerializer) {
    this.rdf = rdf;
    this.contentAddressableRdfSerializer = contentAddressableRdfSerializer;
  }

  @Override
  public IRI generateIri(ReadableGraph graph) {
    if (graph == null || graph.size() == 0) {
      throw new IllegalArgumentException("Graph cannot be null or empty");
    }

    List<Triple> triples = graph.stream().collect(Collectors.toList());

    long iriSubjects = triples.stream().map(Triple::getSubject).filter(IRI.class::isInstance).distinct().count();
    if (iriSubjects != 1) {
      throw new IllegalArgumentException(
          "Content addressing describes exactly one resource, so the graph must hold triples of "
              + "exactly one IRI subject, but holds " + iriSubjects);
    }

    ContentAddressableResult result;
    try {
      result = contentAddressableRdfSerializer.serializeWithUrn(triples);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ContentAddressingException("Failed to generate content-addressed IRI", e);
    }

    String iriString = result.iris()
        .findFirst()
        .orElseThrow(() -> new ContentAddressingException("Serialization yielded no content-addressed IRI", null))
        .getIRIString();

    log.debug("Generated content-addressed IRI: {} for graph with {} triples", iriString, graph.size());

    return rdf.createIRI(iriString);
  }
}
