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
 * CBOR-based content-addressed IRI generator.
 *
 * <p>This implementation uses CBOR (Compact Binary Object Representation) for
 * deterministic RDF serialization and generates content identifiers (CIDs)
 * based on cryptographic hashes of the normalized RDF content.</p>
 *
 * <p>Identical RDF graphs (regardless of syntactic variations) will always
 * produce the same CID.</p>
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

    ContentAddressableResult result;
    try {
      result = contentAddressableRdfSerializer.serializeWithUrn(triples);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to generate content-addressed IRI", e);
    }

    if (result.iris().count() != 1) {
      throw new IllegalStateException(
          "Expected exactly one IRI from content-based addressing, but is: " + result.iris().count());
    }

    String iriString = result.iris().findFirst().orElseThrow().getIRIString();

    log.debug("Generated content-addressed IRI: {} for graph with {} triples", iriString, graph.size());

    return rdf.createIRI(iriString);
  }
}
