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
 * Deduplication (ni-rdf/1 §4.2) and reachability (§3 condition 8) do not depend on the
 * {@code equals} of foreign term implementations: terms with identity {@code equals} mint the
 * name {@link SimpleRdf} terms of the same content mint.
 */
class ForeignTermIdentityTest {

  private static final String EX = "http://example.org/";

  private RDF rdf;
  private ContentAddressedIriGenerator generator;
  private IRI base;

  @BeforeEach
  void setUp() {
    rdf = new SimpleRdf();
    generator = new ContentAddressedIriGeneratorSexpr(rdf);
    base = rdf.createIRI(EX + "r");
  }

  @Test
  @DisplayName("two instances of a foreign IRI with identity equals still deduplicate \"v\"@EN and \"v\"@en")
  void foreignIriDeduplicates() {
    IRI predicate = rdf.createIRI(EX + "p");
    IRI expected = generator.generateIri(base, graphOf(rdf.createTriple(base, predicate, rdf.createLiteral("v", "EN")),
        rdf.createTriple(base, predicate, rdf.createLiteral("v", "en"))));

    IRI actual = generator.generateIri(base,
        graphOf(triple(base, new IdentityIri(EX + "p"), rdf.createLiteral("v", "EN")),
            triple(base, new IdentityIri(EX + "p"), rdf.createLiteral("v", "en"))));

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  @DisplayName("two instances of a foreign blank node with identity equals are one node for reachability")
  void foreignBlankNodeIsReachable() {
    IRI predicate = rdf.createIRI(EX + "p");
    BlankNode node = rdf.createBlankNode("b");
    IRI expected = generator.generateIri(base,
        graphOf(rdf.createTriple(base, predicate, node), rdf.createTriple(node, predicate, rdf.createLiteral("v"))));

    IRI actual = generator.generateIri(base, graphOf(triple(base, predicate, new IdentityBlankNode("b")),
        triple(new IdentityBlankNode("b"), predicate, rdf.createLiteral("v"))));

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  @DisplayName("a foreign blank node without unique reference is still rejected as an unsupported term")
  void wellFormednessStillCheckedBeforeRecreation() {
    IRI predicate = rdf.createIRI(EX + "p");

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> generator.generateIri(base, graphOf(triple(base, predicate, new IdentityBlankNode(null)))))
        .withMessageStartingWith("[UNSUPPORTED_TERM]");
  }

  private static Triple triple(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
    return new Triple() {
      @Override
      public BlankNodeOrIRI getSubject() {
        return subject;
      }

      @Override
      public IRI getPredicate() {
        return predicate;
      }

      @Override
      public RDFTerm getObject() {
        return object;
      }
    };
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

  /** An IRI that breaks the term contract by inheriting identity {@code equals} from Object. */
  private static final class IdentityIri implements IRI {

    private final String iri;

    IdentityIri(String iri) {
      this.iri = iri;
    }

    @Override
    public String getIRIString() {
      return iri;
    }

    @Override
    public String ntriplesString() {
      return "<" + iri + ">";
    }
  }

  /** A blank node that breaks the term contract by inheriting identity {@code equals} from Object. */
  private static final class IdentityBlankNode implements BlankNode {

    private final String reference;

    IdentityBlankNode(String reference) {
      this.reference = reference;
    }

    @Override
    public String uniqueReference() {
      return reference;
    }

    @Override
    public String ntriplesString() {
      return "_:" + reference;
    }
  }
}
