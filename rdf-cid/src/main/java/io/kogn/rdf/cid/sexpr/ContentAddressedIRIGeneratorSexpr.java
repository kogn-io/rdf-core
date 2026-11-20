// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import io.kogn.rdf.cid.CanonicalizationResourceLimitExceededException;
import io.kogn.rdf.cid.ContentAddressedIRIGenerator;
import io.kogn.rdf.cid.ContentAddressingException;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Content-addressed IRI generator implementing the specification ni-rdf/1
 * ({@code docs/spec/ni-rdf/v1.md} in the kognio-rdf repository), which defines every byte of
 * the derivation.
 *
 * <p>The resource is named by a <em>base IRI</em>, an IRI without {@code #} that the caller
 * passes in rather than having it inferred from the triples. The graph's language tags are
 * lower-cased, the base IRI and its fragment IRIs are mapped onto a reserved placeholder IRI,
 * the blank nodes are labelled with RDFC-1.0 (after a deterministic resource limit, see
 * {@link CanonicalizationResourceLimitExceededException}), and the triples are serialized into a
 * sorted S-expression of length-prefixed fields, hashed with SHA3-256 and returned as an
 * RFC 6920 name {@code ni:///sha3-256;<base64url>}. Identical RDF graphs — regardless of blank
 * node labels, triple order, language tag case or the base IRI — therefore always produce the
 * same identifier. Because the base IRI stays out of the digest, the resource can be renamed to
 * its own identifier (see {@link BaseRebaser}) and deriving again from the renamed triples, with
 * the identifier as base, yields the same identifier.</p>
 *
 * <p>Every precondition failure is an {@link IllegalArgumentException} whose message starts
 * with the failure code of ni-rdf/1 §5 in brackets, e.g. {@code [FOREIGN_SUBJECT]}; an exceeded
 * resource limit is a {@link CanonicalizationResourceLimitExceededException} starting with
 * {@code [RESOURCE_LIMIT]}; a failure of the canonicalization itself is a
 * {@link ContentAddressingException}.</p>
 */
public final class ContentAddressedIRIGeneratorSexpr implements ContentAddressedIRIGenerator {

  private final RDF rdf;
  private final Function<Collection<Triple>, Map<String, String>> canonicalIdentifiers;

  /** Creates a generator using {@link SimpleRdf} to create the resulting IRI. */
  public ContentAddressedIRIGeneratorSexpr() {
    this(new SimpleRdf());
  }

  /**
   * Creates a generator.
   *
   * @param rdf the term factory used to create the resulting IRI
   */
  public ContentAddressedIRIGeneratorSexpr(RDF rdf) {
    this(rdf, new RDFDatasetCanonicalizer()::canonicalIdentifiers);
  }

  /** Test seam: creates a generator whose blank node labelling (§4.4) is {@code canonicalIdentifiers}. */
  ContentAddressedIRIGeneratorSexpr(RDF rdf, Function<Collection<Triple>, Map<String, String>> canonicalIdentifiers) {
    this.rdf = Objects.requireNonNull(rdf, "rdf must not be null");
    this.canonicalIdentifiers = Objects.requireNonNull(canonicalIdentifiers, "canonicalIdentifiers must not be null");
  }

  @Override
  public String procedureTag() {
    return HashedInputEncoder.PROCEDURE_TAG;
  }

  @Override
  public IRI generateIRI(IRI base, ReadableGraph graph) {
    Objects.requireNonNull(base, "base must not be null");
    Objects.requireNonNull(graph, "graph must not be null");
    return derive(base, graph.stream().toList()).identifier();
  }

  /**
   * Runs the procedure of ni-rdf/1 §1.3 on the triples describing the resource {@code base}.
   *
   * @param base the base IRI of the resource the triples describe
   * @param triples the input graph describing that resource
   * @return the identifier together with the hashed input it was derived from
   * @throws NullPointerException if {@code base} or {@code triples} is null
   * @throws IllegalArgumentException if a precondition of §3 does not hold; the message then
   *         starts with its failure code in brackets, the first failing one in the order of §3
   * @throws CanonicalizationResourceLimitExceededException if the cost bound of the blank nodes
   *         exceeds the resource limit of §4.3
   * @throws ContentAddressingException if canonicalization otherwise fails
   */
  Derivation derive(IRI base, Collection<Triple> triples) {
    String baseIRI = Preconditions.checkTerms(base, triples);
    List<Triple> input = Preconditions.recreated(rdf, triples);
    Preconditions.checkGraph(input, baseIRI);

    List<Triple> mappedGraph = new BaseMapping(rdf).mappedGraph(input, baseIRI);
    Map<String, String> identifiers = canonicalIdentifiers.apply(mappedGraph);
    byte[] hashedInput = HashedInputEncoder.encode(mappedGraph, identifiers);
    return new Derivation(rdf.createIRI(NiName.of(hashedInput)), hashedInput);
  }
}
