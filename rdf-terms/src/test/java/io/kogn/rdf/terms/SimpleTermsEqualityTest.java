// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pins the component-wise equality and hash contract of the {@link SimpleRdf} terms and triples. */
class SimpleTermsEqualityTest {

  private static final String EX = "http://example.org/";

  private final RDF rdf = new SimpleRdf();

  @Test
  void iriEqualityFollowsTheIriString() {
    assertThat(rdf.createIRI(EX + "a")).isEqualTo(rdf.createIRI(EX + "a"))
        .hasSameHashCodeAs(rdf.createIRI(EX + "a"))
        .isNotEqualTo(rdf.createIRI(EX + "b"));
  }

  @Test
  void literalEqualityComparesLexicalFormDatatypeAndLanguage() {
    assertThat(rdf.createLiteral("x", "en")).isEqualTo(rdf.createLiteral("x", "en"))
        .hasSameHashCodeAs(rdf.createLiteral("x", "en"))
        .isNotEqualTo(rdf.createLiteral("x", "de"))
        .isNotEqualTo(rdf.createLiteral("y", "en"))
        .isNotEqualTo(rdf.createLiteral("x"));
    assertThat(rdf.createLiteral("1", rdf.createIRI(EX + "t"))).isNotEqualTo(rdf.createLiteral("1"));
  }

  @Test
  void blankNodeEqualityFollowsTheIdentifier() {
    assertThat(rdf.createBlankNode("b")).isEqualTo(rdf.createBlankNode("b"))
        .hasSameHashCodeAs(rdf.createBlankNode("b"))
        .isNotEqualTo(rdf.createBlankNode("c"));
  }

  @Test
  void tripleEqualityIsComponentWise() {
    final IRI s = rdf.createIRI(EX + "s");
    final IRI p = rdf.createIRI(EX + "p");

    assertThat(rdf.createTriple(s, p, rdf.createLiteral("o"))).isEqualTo(rdf.createTriple(s, p, rdf.createLiteral("o")))
        .hasSameHashCodeAs(rdf.createTriple(s, p, rdf.createLiteral("o")))
        .isNotEqualTo(rdf.createTriple(s, p, rdf.createLiteral("other")));
  }

  @Test
  void tripleToStringJoinsTheTermsWithATerminatingDot() {
    final Triple triple = rdf.createTriple(rdf.createBlankNode("b"), rdf.createIRI(EX + "p"), rdf.createLiteral("o"));

    assertThat(triple.toString()).startsWith("_:b ").endsWith(" .");
  }
}
