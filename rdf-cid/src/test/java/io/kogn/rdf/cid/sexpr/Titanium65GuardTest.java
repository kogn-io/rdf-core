// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.kogn.rdf.cid.ContentAddressingException;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * The temporary guard against titanium-rdf-canon#65 as seen from the serializer: it looks at the
 * <em>mapped</em> graph (the base IRI is gone by then) and at every position of every term.
 */
class Titanium65GuardTest {

  private static final String EX = "http://example.org/";
  private static final String SUPPLEMENTARY = "😀";
  private static final String UPPER_BMP = "";

  private final RDF rdf = new SimpleRdf();
  private final ContentAddressableRdfSerializer serializer = new ContentAddressableRdfSerializer(
      new RdfDatasetCanonicalizer(), rdf);

  /** Where a code point can sit in the graph built by {@link #graphWith}. */
  enum Position {
    SUBJECT_FRAGMENT, PREDICATE, OBJECT_IRI, LEXICAL_FORM, DATATYPE, LANGUAGE_TAG
  }

  @Test
  @DisplayName("supplementary code points only in the base IRI do not trigger the guard: the mapped graph has none")
  void supplementaryOnlyInTheBaseIsMappedAway() {
    IRI base = rdf.createIRI(EX + SUPPLEMENTARY);
    IRI plainBase = rdf.createIRI(EX + "doc");

    // With the base IRI still in place, "…😀" next to U+E000 would trigger the guard.
    List<Triple> triples = triplesWith(base, "p", UPPER_BMP);
    List<Triple> plainTriples = triplesWith(plainBase, "p", UPPER_BMP);

    assertThat(serializer.serializeWithIri(base, triples).iri())
        .isEqualTo(serializer.serializeWithIri(plainBase, plainTriples).iri());
  }

  @Test
  @DisplayName("the same holds for the base IRI as a datatype and as the base of a fragment")
  void supplementaryOnlyInTheBaseUnderFragmentAndDatatype() {
    IRI base = rdf.createIRI(EX + SUPPLEMENTARY);
    BlankNode b = rdf.createBlankNode("b");
    List<Triple> triples = List.of(rdf.createTriple(rdf.createIRI(base.getIRIString() + "#f"), pred("p"), b),
        rdf.createTriple(b, pred("q"), rdf.createLiteral("v", rdf.createIRI(base.getIRIString()))),
        rdf.createTriple(b, pred("r"), rdf.createLiteral(UPPER_BMP)));

    assertThat(serializer.serializeWithIri(base, triples).iri().getIRIString()).startsWith("ni:///sha3-256;");
  }

  static Stream<Arguments> everyOrderedPairOfPositions() {
    return Stream.of(Position.values())
        .flatMap(supplementary -> Stream.of(Position.values())
            .filter(upperBmp -> upperBmp != supplementary)
            .map(upperBmp -> Arguments.of(supplementary, upperBmp)));
  }

  @ParameterizedTest(name = "supplementary in {0}, U+E000 in {1}")
  @MethodSource("everyOrderedPairOfPositions")
  @DisplayName("a supplementary and an upper-BMP code point trigger the guard whichever positions they sit in")
  void everyPositionIsSeen(Position supplementary, Position upperBmp) {
    List<Triple> triples = graphWith(supplementary, upperBmp);

    assertThatExceptionOfType(ContentAddressingException.class)
        .isThrownBy(() -> serializer.serializeWithIri(rdf.createIRI(EX + "doc"), triples))
        .withMessageContaining("titanium-rdf-canon#65");
  }

  @ParameterizedTest(name = "{0} in every position")
  @MethodSource("oneKindOfCodePoint")
  @DisplayName("one kind of code point in every position needs no guard")
  void oneKindInEveryPositionPasses(String kind, String codePoint) {
    List<Triple> triples = graphWith(position -> codePoint);

    assertThat(serializer.serializeWithIri(rdf.createIRI(EX + "doc"), triples).iri().getIRIString())
        .startsWith("ni:///sha3-256;");
  }

  static Stream<Arguments> oneKindOfCodePoint() {
    return Stream.of(Arguments.of("supplementary", SUPPLEMENTARY), Arguments.of("U+E000", UPPER_BMP));
  }

  private List<Triple> triplesWith(IRI base, String predicate, String lexicalForm) {
    BlankNode b = rdf.createBlankNode("b");
    return List.of(rdf.createTriple(base, pred(predicate), b),
        rdf.createTriple(b, pred("q"), rdf.createLiteral(lexicalForm)));
  }

  private IRI pred(String name) {
    return rdf.createIRI(EX + name);
  }

  private List<Triple> graphWith(Position supplementary, Position upperBmp) {
    return graphWith(position -> position == supplementary ? SUPPLEMENTARY : position == upperBmp ? UPPER_BMP : "x");
  }

  /**
   * One blank node under {@code <doc#…>} with an IRI object, a typed literal and a tagged
   * literal, so every position exists; {@code text} says what goes into each.
   */
  private List<Triple> graphWith(Function<Position, String> text) {
    BlankNode b = rdf.createBlankNode("b");
    return List.of(
        rdf.createTriple(rdf.createIRI(EX + "doc#" + text.apply(Position.SUBJECT_FRAGMENT)),
            pred("p" + text.apply(Position.PREDICATE)), b),
        rdf.createTriple(b, pred("o"), rdf.createIRI(EX + "o" + text.apply(Position.OBJECT_IRI))),
        rdf.createTriple(b, pred("t"),
            rdf.createLiteral(text.apply(Position.LEXICAL_FORM),
                rdf.createIRI(EX + "d" + text.apply(Position.DATATYPE)))),
        rdf.createTriple(b, pred("l"), rdf.createLiteral("v", "x-" + text.apply(Position.LANGUAGE_TAG))));
  }
}
