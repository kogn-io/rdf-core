// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Pins the hashed input of ni-rdf/1 §4.5 byte for byte: the expected strings below are written
 * out by hand from the specification (UTF-8 byte lengths in ASCII decimal, unsigned byte order),
 * not computed by the code under test. Graphs without blank nodes, so RDFC-1.0 plays no part.
 */
class SerializationEncodingTest {

  private static final String EX = "http://example.org/";
  private static final String BASE = EX + "doc";
  private static final String SMILEY = "😀";
  private static final String PREFIX = "(8:ni-rdf/1";

  /** {@code LS("I")} and {@code LS(<xsd:string>)}: the datatype of a plain literal. */
  private static final String XSD_STRING = "1:I39:http://www.w3.org/2001/XMLSchema#string";
  private static final String LANG_STRING = "1:I53:http://www.w3.org/1999/02/22-rdf-syntax-ns#langString";
  private static final String SELF = "1:S";
  private static final String PREDICATE = "1:I20:http://example.org/p";

  private final ContentAddressableRdfSerializer serializer = new ContentAddressableRdfSerializer(
      new RdfDatasetCanonicalizer());
  private final RDF rdf = new SimpleRdf();
  private final IRI base = rdf.createIRI(BASE);
  private final IRI p = rdf.createIRI(EX + "p");

  static Stream<Arguments> fourByteCodePoints() {
    return Stream.of(
        Arguments.of("in an object IRI (23 bytes, not 21 UTF-16 units)", "object",
            "(" + SELF + PREDICATE + "1:I23:http://example.org/" + SMILEY + ")"),
        Arguments.of("in a predicate (24 bytes)", "predicate",
            "(" + SELF + "1:I24:http://example.org/p" + SMILEY + "1:L1:v" + XSD_STRING + "0:)"),
        Arguments.of("in a datatype IRI (24 bytes)", "datatype",
            "(" + SELF + PREDICATE + "1:L1:v1:I24:http://example.org/d" + SMILEY + "0:)"),
        Arguments.of("in a fragment, <base#" + SMILEY + "> as 1:F4:" + SMILEY, "fragment",
            "(1:F4:" + SMILEY + PREDICATE + "1:L1:v" + XSD_STRING + "0:)"),
        Arguments.of("in a language tag (7 bytes)", "language",
            "(" + SELF + PREDICATE + "1:L1:v" + LANG_STRING + "7:en-" + SMILEY + ")"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fourByteCodePoints")
  @DisplayName("a code point beyond the BMP counts as four bytes, in every position")
  void utf8LengthBeyondTheBmp(String position, String where, String expectedTriple) {
    Triple triple = switch (where) {
    case "object" -> rdf.createTriple(base, p, rdf.createIRI(EX + SMILEY));
    case "predicate" -> rdf.createTriple(base, rdf.createIRI(EX + "p" + SMILEY), rdf.createLiteral("v"));
    case "datatype" -> rdf.createTriple(base, p, rdf.createLiteral("v", rdf.createIRI(EX + "d" + SMILEY)));
    case "fragment" -> rdf.createTriple(rdf.createIRI(BASE + "#" + SMILEY), p, rdf.createLiteral("v"));
    case "language" -> rdf.createTriple(base, p, rdf.createLiteral("v", "en-" + SMILEY));
    default -> throw new IllegalArgumentException(where);
    };

    assertThat(hashedInput(List.of(triple))).as(position).isEqualTo(PREFIX + expectedTriple + ")");
  }

  @Test
  @DisplayName("triples whose encodings differ first at a byte of 0x80 or more sort by unsigned byte value: \"~a\" before \"é\"")
  void unsignedByteOrderAgainstAscii() {
    List<Triple> triples = List.of(literalTriple("é"), literalTriple("~a"));

    assertThat(hashedInput(triples)).isEqualTo(PREFIX + "(" + SELF + PREDICATE + "1:L2:~a" + XSD_STRING + "0:)" + "("
        + SELF + PREDICATE + "1:L2:é" + XSD_STRING + "0:))");
  }

  @Test
  @DisplayName("U+E000 sorts before U+1F600 (code point order, which UTF-16 order would reverse), whatever the input order")
  void codePointOrderBeyondTheBmp() {
    String privateUse = "a";

    String expected = PREFIX + "(" + SELF + PREDICATE + "1:L4:" + privateUse + XSD_STRING + "0:)" + "(" + SELF
        + PREDICATE + "1:L4:" + SMILEY + XSD_STRING + "0:))";
    assertThat(hashedInput(List.of(literalTriple(SMILEY), literalTriple(privateUse)))).isEqualTo(expected);
    assertThat(hashedInput(List.of(literalTriple(privateUse), literalTriple(SMILEY)))).isEqualTo(expected);
  }

  private Triple literalTriple(String lexicalForm) {
    RDFTerm literal = rdf.createLiteral(lexicalForm);
    return rdf.createTriple(base, p, literal);
  }

  private String hashedInput(List<Triple> triples) {
    return new String(serializer.serializeWithIri(base, triples).sexprBytes(), StandardCharsets.UTF_8);
  }
}
