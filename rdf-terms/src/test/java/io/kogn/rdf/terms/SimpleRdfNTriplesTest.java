// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pins {@link RDFTerm#ntriplesString()} of the {@link SimpleRdf} terms to the RDF 1.1 N-Triples grammar. */
class SimpleRdfNTriplesTest {

  private final RDF rdf = new SimpleRdf();

  @Test
  void iriIsWrappedInAngleBrackets() {
    assertThat(rdf.createIRI("http://example.org/a").ntriplesString()).isEqualTo("<http://example.org/a>");
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://a b", "http://a<b", "http://a>b", "http://a\"b", "http://a{b", "http://a}b",
      "http://a|b", "http://a^b", "http://a`b", "http://a\\b", "http://a\nb", "http://a\u0000b"})
  void iriWithAGrammarForbiddenCharacterIsRejected(final String iri) {
    final IRI term = rdf.createIRI(iri);

    assertThatThrownBy(term::ntriplesString).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("N-Triples");
  }

  @Test
  void toStringOfAnUnserializableIriDoesNotThrow() {
    assertThat(rdf.createIRI("http://a b").toString()).contains("http://a b");
  }

  @Test
  void plainLiteralCarriesXsdString() {
    assertThat(rdf.createLiteral("hi").ntriplesString()).isEqualTo("\"hi\"^^<http://www.w3.org/2001/XMLSchema#string>");
  }

  @Test
  void languageLiteralCarriesTheTagOnly() {
    assertThat(rdf.createLiteral("hi", "en-GB").ntriplesString()).isEqualTo("\"hi\"@en-GB");
  }

  @Test
  void typedLiteralCarriesTheDatatype() {
    assertThat(rdf.createLiteral("1", rdf.createIRI("http://www.w3.org/2001/XMLSchema#integer")).ntriplesString())
        .isEqualTo("\"1\"^^<http://www.w3.org/2001/XMLSchema#integer>");
  }

  @Test
  void literalEscapesQuoteBackslashAndLineBreaks() {
    assertThat(rdf.createLiteral("a\"b\\c\nd\re", "en").ntriplesString()).isEqualTo("\"a\\\"b\\\\c\\nd\\re\"@en");
  }

  @Test
  void literalLeavesNonAsciiCharactersUntouched() {
    assertThat(rdf.createLiteral("Grüße 😀", "de").ntriplesString()).isEqualTo("\"Grüße 😀\"@de");
  }

  @ParameterizedTest
  @ValueSource(strings = {"e n", "-en", "en-", "123", "en_GB"})
  void literalWithAMalformedLanguageTagIsRejected(final String tag) {
    final Literal literal = rdf.createLiteral("x", tag);

    assertThatThrownBy(literal::ntriplesString).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("N-Triples");
  }

  @Test
  void literalWithAnUnserializableDatatypeIsRejected() {
    final Literal literal = rdf.createLiteral("x", rdf.createIRI("http://a b"));

    assertThatThrownBy(literal::ntriplesString).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blankNodeUsesTheLabelSyntax() {
    assertThat(rdf.createBlankNode("b1").ntriplesString()).isEqualTo("_:b1");
    assertThat(rdf.createBlankNode("1.a-b_c:d").ntriplesString()).isEqualTo("_:1.a-b_c:d");
  }

  @ParameterizedTest
  @ValueSource(strings = {"a b", "a.", ".a", "-a", "a<b"})
  void blankNodeWithAMalformedLabelIsRejected(final String label) {
    final BlankNode node = rdf.createBlankNode(label);

    assertThatThrownBy(node::ntriplesString).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("N-Triples");
  }

  @Test
  void generatedBlankNodeIsSerializable() {
    assertThat(rdf.createBlankNode().ntriplesString()).startsWith("_:").doesNotContain(" ");
  }
}
