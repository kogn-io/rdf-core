// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.kogn.rdf.cid.CanonicalizationResourceLimitExceededException;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * The failure codes of ni-rdf/1 section 5 and the order of the section 3 checks, driven through
 * {@link ContentAddressableRdfSerializer#serializeWithIri}: which code is reported when
 * several conditions fail at once, and that every code comes with its documented type and
 * a {@code [CODE]} message prefix.
 */
class PreconditionCodesTest {

  private static final String EX = "http://example.org/";
  private static final String RESERVED = ContentAddressableRdfSerializer.RESERVED_IRI;
  private static final String BASE = EX + "doc";
  private static final String BLANK_LOOKING = "_:x";

  private static final RDF RDF_FACTORY = new SimpleRdf();

  private final ContentAddressableRdfSerializer serializer = new ContentAddressableRdfSerializer(
      new RdfDatasetCanonicalizer());

  /** One input: a base IRI and a graph. */
  private record Input(String base, List<Triple> triples) {
  }

  private static Triple triple(String subject, String predicate, RDFTerm object) {
    return RDF_FACTORY.createTriple(RDF_FACTORY.createIRI(subject), RDF_FACTORY.createIRI(predicate), object);
  }

  private static IRI iri(String value) {
    return RDF_FACTORY.createIRI(value);
  }

  /** A valid graph for {@code BASE}. */
  private static Triple fine() {
    return triple(BASE, EX + "p", RDF_FACTORY.createLiteral("v"));
  }

  /** Condition 4: an IRI that reads as a blank node label. */
  private static Triple blankNodeLookingIri() {
    return triple(BASE, EX + "p", iri(BLANK_LOOKING));
  }

  /** Condition 4: a term of a kind RDF 1.1 does not have. */
  private static Triple unknownTermKind() {
    RDFTerm unknown = () -> "unsupported";
    return triple(BASE, EX + "p", unknown);
  }

  /** Condition 5: an IRI with the reserved IRI as its base. */
  private static Triple reservedIri() {
    return triple(BASE, EX + "p", iri(RESERVED + "#x"));
  }

  /** Condition 6: an IRI subject that is not the base. */
  private static Triple foreignSubject() {
    return triple(EX + "other", EX + "p", RDF_FACTORY.createLiteral("v"));
  }

  /** Conditions 7 and 8: a blank node subject, reachable from no IRI subject. */
  private static Triple blankSubjectOnly() {
    return RDF_FACTORY.createTriple(RDF_FACTORY.createBlankNode("b"), iri(EX + "p"), RDF_FACTORY.createLiteral("v"));
  }

  static Stream<Arguments> doubleViolations() {
    String withFragment = BASE + "#a";
    return Stream.of(
        // condition 1 against every later one
        args("1 alone: fragment of the reserved IRI as base is INVALID_BASE (1+2 cannot co-occur)", "INVALID_BASE",
            RESERVED + "#a", List.of(fine())),
        args("1+3: '#' in the base, empty graph", "INVALID_BASE", withFragment, List.of()),
        args("1+4: '#' in the base, unsupported term", "INVALID_BASE", withFragment, List.of(blankNodeLookingIri())),
        args("1+5: '#' in the base, reserved IRI in graph", "INVALID_BASE", withFragment, List.of(reservedIri())),
        args("1+6: '#' in the base, foreign subject", "INVALID_BASE", withFragment, List.of(foreignSubject())),
        args("1+7: '#' in the base, no IRI subject", "INVALID_BASE", withFragment, List.of(blankSubjectOnly())),
        // condition 2
        args("2+3: base is the reserved IRI, empty graph", "RESERVED_IRI", RESERVED, List.of()),
        args("2+4: base is the reserved IRI, unsupported term", "RESERVED_IRI", RESERVED,
            List.of(blankNodeLookingIri())),
        args("2+6: base is the reserved IRI, foreign subject", "RESERVED_IRI", RESERVED, List.of(foreignSubject())),
        args("2+7: base is the reserved IRI, no IRI subject", "RESERVED_IRI", RESERVED, List.of(blankSubjectOnly())),
        // condition 3: an empty graph can only be beaten by a bad base, which is condition 4 here
        args("3+4: empty graph, base reads as a blank node label", "EMPTY_GRAPH", BLANK_LOOKING, List.of()),
        // condition 4 against every later one
        args("4+5 (IRI): blank-node-looking IRI and reserved IRI", "UNSUPPORTED_TERM", BASE,
            List.of(blankNodeLookingIri(), reservedIri())),
        args("4+5 (kind): unknown term kind and reserved IRI", "UNSUPPORTED_TERM", BASE,
            List.of(unknownTermKind(), reservedIri())),
        args("4+5 (base): base reads as a blank node label, reserved IRI in graph", "UNSUPPORTED_TERM", BLANK_LOOKING,
            List.of(triple(BLANK_LOOKING, EX + "p", iri(RESERVED + "#x")))),
        args("4+6: unsupported term and foreign subject", "UNSUPPORTED_TERM", BASE,
            List.of(blankNodeLookingIri(), foreignSubject())),
        args("4+7: unsupported term and no IRI subject", "UNSUPPORTED_TERM", BASE,
            List.of(blankSubjectWith(iri(BLANK_LOOKING)))),
        args("4+8: unsupported term and an unreachable triple", "UNSUPPORTED_TERM", BASE,
            List.of(blankNodeLookingIri(), blankSubjectOnly())),
        // condition 5
        args("5+6: reserved IRI and foreign subject", "RESERVED_IRI", BASE, List.of(reservedIri(), foreignSubject())),
        args("5+7: reserved IRI and no IRI subject", "RESERVED_IRI", BASE,
            List.of(blankSubjectWith(iri(RESERVED + "#x")))),
        args("5+8: reserved IRI and an unreachable triple", "RESERVED_IRI", BASE,
            List.of(reservedIri(), blankSubjectOnly())),
        // condition 6
        args("6+8: foreign subject and an unreachable triple", "FOREIGN_SUBJECT", BASE,
            List.of(foreignSubject(), blankSubjectOnly())),
        // condition 7 before 8: a graph without IRI subject is unreachable throughout
        args("7+8: no IRI subject, so nothing is reachable either", "NO_ROOT", BASE, List.of(blankSubjectOnly())),
        // section 3 before the resource limit of section 4.3
        args("1+limit: '#' in the base, graph over the cost limit", "INVALID_BASE", withFragment, overLimit()),
        args("2+limit: reserved base, graph over the cost limit", "RESERVED_IRI", RESERVED, overLimit()),
        args("4+limit: unsupported term, graph over the cost limit", "UNSUPPORTED_TERM", BASE,
            with(overLimit(), blankNodeLookingIri())),
        args("5+limit: reserved IRI, graph over the cost limit", "RESERVED_IRI", BASE,
            with(overLimit(), reservedIri())),
        args("6+limit: foreign subject, graph over the cost limit", "FOREIGN_SUBJECT", BASE,
            with(overLimit(), foreignSubject())),
        args("8+limit: unreachable triple, graph over the cost limit", "UNREACHABLE", BASE,
            with(overLimit(), blankSubjectOnly())));
  }

  private static Triple blankSubjectWith(RDFTerm object) {
    return RDF_FACTORY.createTriple(RDF_FACTORY.createBlankNode("b"), iri(EX + "p"), object);
  }

  private static Arguments args(String name, String code, String base, List<Triple> triples) {
    return Arguments.of(name, code, base, triples);
  }

  private static List<Triple> with(List<Triple> triples, Triple extra) {
    List<Triple> all = new ArrayList<>(triples);
    all.add(extra);
    return all;
  }

  /** Two blank nodes with 8 identical blank-node children each: E = 65 318 400 &gt; 10 000 000. */
  private static List<Triple> overLimit() {
    List<Triple> triples = new ArrayList<>();
    for (int hub = 0; hub < 2; hub++) {
      BlankNode hubNode = RDF_FACTORY.createBlankNode("h" + hub);
      triples.add(RDF_FACTORY.createTriple(iri(BASE), iri(EX + "member"), hubNode));
      for (int leaf = 0; leaf < 8; leaf++) {
        triples.add(
            RDF_FACTORY.createTriple(hubNode, iri(EX + "item"), RDF_FACTORY.createBlankNode("l" + hub + "_" + leaf)));
      }
    }
    return triples;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("doubleViolations")
  @DisplayName("when several conditions fail, the first one in the order of section 3 is reported")
  void firstFailingConditionIsReported(String name, String code, String base, List<Triple> triples) {
    assertThatThrownBy(() -> serializer.serializeWithIri(iri(base), triples)).as(name)
        .hasMessageStartingWith("[" + code + "]");
  }

  /** Every code of section 5 with a way to provoke it alone, and the type its failure must have. */
  static Stream<Arguments> everyCode() {
    return Stream.of(code("INVALID_BASE", BASE + "#a", List.of(fine()), IllegalArgumentException.class),
        code("RESERVED_IRI", RESERVED, List.of(fine()), IllegalArgumentException.class),
        code("RESERVED_IRI", BASE, List.of(reservedIri()), IllegalArgumentException.class),
        code("EMPTY_GRAPH", BASE, List.of(), IllegalArgumentException.class),
        code("UNSUPPORTED_TERM", BASE, List.of(blankNodeLookingIri()), IllegalArgumentException.class),
        code("UNSUPPORTED_TERM", BASE, List.of(unknownTermKind()), IllegalArgumentException.class),
        code("FOREIGN_SUBJECT", BASE, List.of(foreignSubject()), IllegalArgumentException.class),
        code("NO_ROOT", BASE, List.of(blankSubjectOnly()), IllegalArgumentException.class),
        code("UNREACHABLE", BASE, with(List.of(fine()), blankSubjectOnly()), IllegalArgumentException.class),
        code("RESOURCE_LIMIT", BASE, overLimit(), CanonicalizationResourceLimitExceededException.class));
  }

  private static Arguments code(String code, String base, List<Triple> triples, Class<? extends Throwable> type) {
    return Arguments.of(code, base, triples, type);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyCode")
  @DisplayName("every section 5 code is thrown with its documented type and starts the message in brackets")
  void everyCodeHasItsTypeAndPrefix(String code, String base, List<Triple> triples, Class<? extends Throwable> type) {
    assertThatThrownBy(() -> serializer.serializeWithIri(iri(base), triples)).isExactlyInstanceOf(type)
        .hasMessageStartingWith("[" + code + "] ");
  }

  @Test
  @DisplayName("the provoked codes are exactly the eight codes of section 5")
  void allEightCodesAreCovered() {
    List<String> provoked = everyCode().map(a -> (String) a.get()[0]).distinct().toList();

    assertThat(provoked).containsExactlyInAnyOrder("INVALID_BASE", "RESERVED_IRI", "EMPTY_GRAPH", "UNSUPPORTED_TERM",
        "FOREIGN_SUBJECT", "NO_ROOT", "UNREACHABLE", "RESOURCE_LIMIT");
  }
}
