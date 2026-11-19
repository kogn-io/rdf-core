// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.util.List;
import java.util.Objects;

import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.Triple;

/**
 * Applies §4.1 and §4.2 of ni-rdf/1: language tags lower-cased, the base IRI and its fragment
 * IRIs mapped onto the reserved IRI in every position, the datatype IRI of a literal without a
 * language tag included ({@code rdf:langString} is never mapped).
 */
final class BaseMapping {

  private final RDF rdf;

  BaseMapping(RDF rdf) {
    this.rdf = Objects.requireNonNull(rdf, "rdf must not be null");
  }

  /**
   * Maps the graph. Mapped triples that become equal (two spellings of one language tag)
   * collapse into one, as RDF 1.1 compares language tags case-insensitively.
   *
   * @param graph the input graph, its terms recreated through the term factory
   * @param base the IRI string of the base IRI
   * @return the mapped graph (§1.2), without duplicates
   */
  List<Triple> mappedGraph(List<Triple> graph, String base) {
    return graph.stream().map(triple -> mapped(triple, base)).distinct().toList();
  }

  private Triple mapped(Triple triple, String base) {
    BlankNodeOrIRI subject = triple.getSubject() instanceof IRI iri ? mapped(iri, base) : triple.getSubject();
    RDFTerm object = switch (triple.getObject()) {
    case IRI iri -> mapped(iri, base);
    case Literal literal -> mapped(literal, base);
    default -> triple.getObject();
    };
    return rdf.createTriple(subject, mapped(triple.getPredicate(), base), object);
  }

  private Literal mapped(Literal literal, String base) {
    if (literal.getLanguageTag().isPresent()) {
      return rdf.createLiteral(literal.getLexicalForm(), Terms.lowerCaseLanguageTag(literal.getLanguageTag().get()));
    }
    return rdf.createLiteral(literal.getLexicalForm(), rdf.createIRI(mapped(Terms.datatypeOf(literal), base)));
  }

  private IRI mapped(IRI iri, String base) {
    String value = iri.getIRIString();
    String mappedValue = mapped(value, base);
    return mappedValue.equals(value) ? iri : rdf.createIRI(mappedValue);
  }

  /** The base IRI becomes the reserved IRI, {@code base#f} becomes {@code reserved#f}, any other IRI stays. */
  private static String mapped(String iri, String base) {
    if (iri.equals(base)) {
      return Terms.RESERVED_IRI;
    }
    if (iri.length() > base.length() && iri.startsWith(base) && iri.charAt(base.length()) == Terms.FRAGMENT_SEPARATOR) {
      return Terms.RESERVED_FRAGMENT_PREFIX + iri.substring(base.length() + 1);
    }
    return iri;
  }
}
