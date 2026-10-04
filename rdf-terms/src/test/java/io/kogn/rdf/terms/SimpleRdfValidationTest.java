// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Pins the argument validation of {@link SimpleRdf}, which must reject what the RDF4J backend rejects. */
class SimpleRdfValidationTest {

  private static final String LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString";
  private static final String EX = "http://example.org/";

  private final RDF rdf = new SimpleRdf();

  @Test
  void createIriRejectsNull() {
    assertThatThrownBy(() -> rdf.createIRI(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void createIriRejectsEmptyAndNonAbsoluteStrings() {
    assertThatThrownBy(() -> rdf.createIRI("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rdf.createIRI("no-colon")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createLiteralRejectsNullLexicalForm() {
    assertThatThrownBy(() -> rdf.createLiteral(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> rdf.createLiteral(null, "en")).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> rdf.createLiteral(null, rdf.createIRI(EX + "dt")))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void createLiteralAcceptsTheEmptyLexicalForm() {
    assertThat(rdf.createLiteral("").getLexicalForm()).isEmpty();
  }

  @Test
  void createLiteralRejectsNullOrEmptyLanguageTag() {
    assertThatThrownBy(() -> rdf.createLiteral("x", (String) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> rdf.createLiteral("x", "")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createLiteralRejectsNullDatatype() {
    assertThatThrownBy(() -> rdf.createLiteral("x", (IRI) null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void createLiteralRejectsLangStringDatatypeWithoutLanguageTag() {
    final IRI langString = rdf.createIRI(LANG_STRING);

    assertThatThrownBy(() -> rdf.createLiteral("x", langString)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("language tag");
  }

  @Test
  void createLiteralWithLanguageTagHasLangStringDatatype() {
    final Literal literal = rdf.createLiteral("x", "en");

    assertThat(literal.getDatatype().getIRIString()).isEqualTo(LANG_STRING);
    assertThat(literal.getLanguageTag()).contains("en");
  }

  @Test
  void createBlankNodeRejectsNullAndEmptyIdentifier() {
    assertThatThrownBy(() -> rdf.createBlankNode(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> rdf.createBlankNode("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createTripleRejectsNullComponents() {
    final IRI s = rdf.createIRI(EX + "s");

    assertThatThrownBy(() -> rdf.createTriple(null, s, s)).isInstanceOf(NullPointerException.class)
        .hasMessageContaining("subject");
    assertThatThrownBy(() -> rdf.createTriple(s, null, s)).isInstanceOf(NullPointerException.class)
        .hasMessageContaining("predicate");
    assertThatThrownBy(() -> rdf.createTriple(s, s, null)).isInstanceOf(NullPointerException.class)
        .hasMessageContaining("object");
  }
}
