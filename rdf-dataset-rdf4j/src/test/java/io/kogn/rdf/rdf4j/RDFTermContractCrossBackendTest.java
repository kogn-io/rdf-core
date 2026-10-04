// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;

/**
 * Pins the {@link RDF} term contract (argument validation, N-Triples serialization) for both backends: a test
 * backend that accepts more than production, or serializes differently, is the defect itself.
 */
class RDFTermContractCrossBackendTest {

  private static final String LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString";
  private static final String EX = "http://example.org/";

  static Stream<Arguments> backends() {
    return Stream.of(Arguments.of("simple", new SimpleRdf()), Arguments.of("rdf4j", new RDF4JFactory()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("backends")
  void nullArgumentsAreRejectedWithANullPointerException(final String name, final RDF rdf) {
    final IRI s = rdf.createIRI(EX + "s");

    assertThatThrownBy(() -> rdf.createIRI(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> rdf.createLiteral(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> rdf.createLiteral(null, "en")).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> rdf.createLiteral("x", (String) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> rdf.createLiteral("x", (IRI) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> rdf.createTriple(null, s, s)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> rdf.createTriple(s, null, s)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> rdf.createTriple(s, s, null)).isInstanceOf(NullPointerException.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("backends")
  void emptyOrMalformedArgumentsAreRejectedWithAnIllegalArgumentException(final String name, final RDF rdf) {
    final IRI langString = rdf.createIRI(LANG_STRING);

    assertThatThrownBy(() -> rdf.createIRI("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rdf.createIRI("no-colon")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rdf.createLiteral("x", "")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rdf.createLiteral("x", langString)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("backends")
  void serializesToRdf11NTriples(final String name, final RDF rdf) {
    assertThat(rdf.createIRI(EX + "a").ntriplesString()).isEqualTo("<http://example.org/a>");
    assertThat(rdf.createLiteral("hi").ntriplesString()).isEqualTo("\"hi\"^^<http://www.w3.org/2001/XMLSchema#string>");
    assertThat(rdf.createLiteral("hi", "en").ntriplesString()).isEqualTo("\"hi\"@en");
    assertThat(rdf.createLiteral("1", rdf.createIRI("http://www.w3.org/2001/XMLSchema#integer")).ntriplesString())
        .isEqualTo("\"1\"^^<http://www.w3.org/2001/XMLSchema#integer>");
    assertThat(rdf.createLiteral("a\"b\\c\nd\re", "en").ntriplesString()).isEqualTo("\"a\\\"b\\\\c\\nd\\re\"@en");
    assertThat(rdf.createBlankNode("b1").ntriplesString()).isEqualTo("_:b1");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("backends")
  void rejectsAnIriWithAGrammarForbiddenCharacterWhenSerializing(final String name, final RDF rdf) {
    final IRI space = rdf.createIRI("http://a b");
    final IRI angle = rdf.createIRI("http://a<b");

    assertThatThrownBy(space::ntriplesString).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(angle::ntriplesString).isInstanceOf(IllegalArgumentException.class);
    assertThat(space.toString()).contains("http://a b");
  }

  @Test
  void simpleAndRdf4jTermsSerializeIdentically() {
    final RDF simple = new SimpleRdf();
    final RDF rdf4j = new RDF4JFactory();
    final Literal[] pairs = {simple.createLiteral("a\"\n", "de"), rdf4j.createLiteral("a\"\n", "de")};
    final BlankNode[] nodes = {simple.createBlankNode("n"), rdf4j.createBlankNode("n")};

    assertThat(pairs[0].ntriplesString()).isEqualTo(pairs[1].ntriplesString());
    assertThat(nodes[0].ntriplesString()).isEqualTo(nodes[1].ntriplesString());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"e n", "-en", "123"})
  void rdf4jRejectsAMalformedLanguageTagWhenSerializing(final String tag) {
    final Literal literal = new RDF4JFactory().createLiteral("x", tag);

    assertThatThrownBy(literal::ntriplesString).isInstanceOf(IllegalArgumentException.class);
  }
}
