// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * A string holding an unpaired surrogate is no sequence of Unicode code points (ni-rdf/1 §1.2),
 * so the term holding it is no RDF 1.1 term. UTF-8 encoding would silently turn the surrogate
 * into {@code ?} and let the name collide with valid content; it is rejected as
 * {@code [UNSUPPORTED_TERM]} in every position instead.
 */
class UnpairedSurrogateTest {

  private static final String EX = "http://example.org/";
  private static final String UNSUPPORTED_TERM = "[UNSUPPORTED_TERM]";
  private static final String HIGH_SURROGATE = "\uD800";
  private static final String LOW_SURROGATE = "\uDC00";
  private static final String SURROGATE_PAIR = "😀";

  private RDF rdf;
  private ContentAddressedIriGenerator generator;
  private IRI base;
  private IRI predicate;

  @BeforeEach
  void setUp() {
    rdf = new SimpleRdf();
    generator = new ContentAddressedIriGeneratorSexpr(rdf);
    base = rdf.createIRI(EX + "r");
    predicate = rdf.createIRI(EX + "p");
  }

  @Test
  @DisplayName("a lexical form holding a lone high surrogate is rejected instead of colliding with \"?\"")
  void loneHighSurrogateInLexicalForm() {
    assertUnsupported(base, rdf.createTriple(base, predicate, rdf.createLiteral(HIGH_SURROGATE)));
  }

  @Test
  @DisplayName("a lexical form holding a lone low surrogate is rejected")
  void loneLowSurrogateInLexicalForm() {
    assertUnsupported(base, rdf.createTriple(base, predicate, rdf.createLiteral(LOW_SURROGATE)));
  }

  @Test
  @DisplayName("a low surrogate followed by a high one is no pair and is rejected")
  void reversedPairInLexicalForm() {
    assertUnsupported(base, rdf.createTriple(base, predicate, rdf.createLiteral(LOW_SURROGATE + HIGH_SURROGATE)));
  }

  @Test
  @DisplayName("the literal \"?\" itself and a proper surrogate pair still mint a name")
  void validStringsStillMintAName() {
    IRI question = generator.generateIri(base, graphOf(rdf.createTriple(base, predicate, rdf.createLiteral("?"))));
    IRI pair = generator.generateIri(base,
        graphOf(rdf.createTriple(base, predicate, rdf.createLiteral(SURROGATE_PAIR))));

    assertThat(question.getIRIString()).startsWith("ni:///sha3-256;");
    assertThat(pair).isNotEqualTo(question);
  }

  @Test
  @DisplayName("a lone surrogate in an IRI object is rejected")
  void loneSurrogateInIriObject() {
    assertUnsupported(base, rdf.createTriple(base, predicate, rdf.createIRI(EX + HIGH_SURROGATE)));
  }

  @Test
  @DisplayName("a lone surrogate in a predicate is rejected")
  void loneSurrogateInPredicate() {
    assertUnsupported(base, rdf.createTriple(base, rdf.createIRI(EX + LOW_SURROGATE), rdf.createLiteral("v")));
  }

  @Test
  @DisplayName("a lone surrogate in a fragment IRI subject is rejected")
  void loneSurrogateInFragmentSubject() {
    IRI fragment = rdf.createIRI(EX + "r#" + HIGH_SURROGATE);
    assertUnsupported(base, rdf.createTriple(base, predicate, fragment),
        rdf.createTriple(fragment, predicate, rdf.createLiteral("v")));
  }

  @Test
  @DisplayName("a lone surrogate in the base IRI is rejected")
  void loneSurrogateInBase() {
    IRI surrogateBase = rdf.createIRI(EX + HIGH_SURROGATE);
    assertUnsupported(surrogateBase, rdf.createTriple(surrogateBase, predicate, rdf.createLiteral("v")));
  }

  @Test
  @DisplayName("a lone surrogate in a datatype IRI is rejected")
  void loneSurrogateInDatatype() {
    assertUnsupported(base,
        rdf.createTriple(base, predicate, rdf.createLiteral("v", rdf.createIRI(EX + LOW_SURROGATE))));
  }

  @Test
  @DisplayName("a lone surrogate in a language tag is rejected")
  void loneSurrogateInLanguageTag() {
    assertUnsupported(base, rdf.createTriple(base, predicate, rdf.createLiteral("v", "en-" + HIGH_SURROGATE)));
  }

  @Test
  @DisplayName("a lone surrogate in a blank node reference is rejected")
  void loneSurrogateInBlankNodeReference() {
    BlankNode node = rdf.createBlankNode(HIGH_SURROGATE);
    assertUnsupported(base, rdf.createTriple(base, predicate, node),
        rdf.createTriple(node, predicate, rdf.createLiteral("v")));
  }

  @Test
  @DisplayName("a lone surrogate is reported as condition 4, before a foreign subject (condition 6)")
  void reportedBeforeForeignSubject() {
    assertUnsupported(base, rdf.createTriple(base, predicate, rdf.createLiteral(HIGH_SURROGATE)),
        rdf.createTriple(rdf.createIRI(EX + "other"), predicate, rdf.createLiteral("v")));
  }

  private void assertUnsupported(IRI baseIri, Triple... triples) {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> generator.generateIri(baseIri, graphOf(triples)))
        .withMessageStartingWith(UNSUPPORTED_TERM)
        .withMessageContaining("unpaired surrogate");
  }

  private static ReadableGraph graphOf(Triple... triples) {
    return new ReadableGraph() {
      @Override
      public boolean contains(Triple triple) {
        throw new UnsupportedOperationException();
      }

      @Override
      public long size() {
        return triples.length;
      }

      @Override
      public Stream<Triple> stream() {
        return Stream.of(triples);
      }

      @Override
      public Stream<Triple> stream(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean isEmpty() {
        return triples.length == 0;
      }
    };
  }
}
