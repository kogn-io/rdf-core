// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.cid.sexpr.ContentAddressableRdfSerializer;
import io.kogn.rdf.cid.sexpr.RdfDatasetCanonicalizer;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * The boundary between caller contract breaches and derivation failures: foreign term
 * implementations that break the term contract are rejected as {@code [UNSUPPORTED_TERM]}, the
 * failure messages never depend on {@code ntriplesString()}, and the resource-limit message
 * does not report the saturated internal cap as the graph's cost bound.
 */
class TranslationBoundaryTest {

  private static final String EX = "http://example.org/";
  private static final String UNSUPPORTED_TERM = "[UNSUPPORTED_TERM]";
  private static final String LONE_SURROGATE = "\uD800";

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
  @DisplayName("a null subject is rejected as an unsupported term, not as a derivation failure")
  void nullSubject() {
    assertUnsupported(triple(null, predicate, rdf.createLiteral("v")));
  }

  @Test
  @DisplayName("a null predicate is rejected as an unsupported term")
  void nullPredicate() {
    assertUnsupported(triple(base, null, rdf.createLiteral("v")));
  }

  @Test
  @DisplayName("a null object is rejected as an unsupported term")
  void nullObject() {
    assertUnsupported(triple(base, predicate, null));
  }

  @Test
  @DisplayName("a null triple is rejected as an unsupported term")
  void nullTriple() {
    assertUnsupported((Triple) null);
  }

  @Test
  @DisplayName("a literal whose language tag Optional is null is rejected as an unsupported term")
  void literalWithNullLanguageTagOptional() {
    Literal foreign = new ForeignLiteral("v", null, rdf.createIRI(EX + "dt"));
    assertUnsupported(triple(base, predicate, foreign));
  }

  @Test
  @DisplayName("a literal without lexical form is rejected as an unsupported term")
  void literalWithNullLexicalForm() {
    Literal foreign = new ForeignLiteral(null, Optional.empty(), null);
    assertUnsupported(triple(base, predicate, foreign));
  }

  @Test
  @DisplayName("a blank node without unique reference is rejected as an unsupported term")
  void blankNodeWithNullUniqueReference() {
    BlankNode first = new ForeignBlankNode();
    BlankNode second = new ForeignBlankNode();
    assertUnsupported(triple(base, predicate, first), triple(first, predicate, second));
  }

  @Test
  @DisplayName("an IRI without IRI string is rejected as an unsupported term")
  void iriWithNullIriString() {
    IRI foreign = new IRI() {
      @Override
      public String getIRIString() {
        return null;
      }

      @Override
      public String ntriplesString() {
        return "foreign";
      }
    };
    assertUnsupported(triple(base, predicate, foreign));
  }

  @Test
  @DisplayName("a base IRI without IRI string is rejected as an unsupported term")
  void baseWithNullIriString() {
    IRI foreign = new IRI() {
      @Override
      public String getIRIString() {
        return null;
      }

      @Override
      public String ntriplesString() {
        return "foreign";
      }
    };
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> generator.generateIri(foreign, graphOf(triple(base, predicate, rdf.createLiteral("v")))))
        .withMessageStartingWith(UNSUPPORTED_TERM);
  }

  @Test
  @DisplayName("an unsupported subject kind next to a lone-surrogate literal is still reported with its code")
  void unsupportedKindBesideLoneSurrogateLiteral() {
    BlankNodeOrIRI unknownKind = () -> "unsupported";
    assertUnsupported(triple(unknownKind, predicate, rdf.createLiteral(LONE_SURROGATE)));
  }

  @Test
  @DisplayName("a blank-node-looking datatype on a lone-surrogate literal is reported with its code")
  void blankNodeLookingDatatypeOnLoneSurrogateLiteral() {
    Literal literal = rdf.createLiteral(LONE_SURROGATE, rdf.createIRI("_:b0"));
    assertUnsupported(triple(base, predicate, literal));
  }

  @Test
  @DisplayName("a failure of the serializer itself is not relabelled as a derivation failure")
  void ownBugIsNotRelabelled() {
    IllegalStateException bug = new IllegalStateException("bug");
    ContentAddressableRdfSerializer broken = new ContentAddressableRdfSerializer(new RdfDatasetCanonicalizer(), rdf) {
      @Override
      public ContentAddressableResult serializeWithIri(IRI iri, java.util.Collection<Triple> triples) {
        throw bug;
      }
    };
    ContentAddressedIriGenerator failing = new ContentAddressedIriGeneratorSexpr(rdf, broken);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> failing.generateIri(base, graphOf(triple(base, predicate, rdf.createLiteral("v")))))
        .isSameAs(bug);
  }

  @Test
  @DisplayName("the resource-limit message names the limit, not the saturated cap of the cost estimate")
  void resourceLimitMessageNamesTheLimit() {
    IRI link = rdf.createIRI(EX + "link");
    List<BlankNode> nodes = new ArrayList<>();
    List<Triple> triples = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      BlankNode node = rdf.createBlankNode("n" + i);
      nodes.add(node);
      triples.add(rdf.createTriple(base, link, node));
    }
    for (BlankNode from : nodes) {
      for (BlankNode to : nodes) {
        if (from != to) {
          triples.add(rdf.createTriple(from, link, to));
        }
      }
    }

    assertThatExceptionOfType(CanonicalizationResourceLimitExceededException.class)
        .isThrownBy(() -> generator.generateIri(base, graphOf(triples.toArray(Triple[]::new))))
        .withMessageStartingWith("[RESOURCE_LIMIT]")
        .withMessageContaining("exceeds 10000000")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("6860000686"));
  }

  private void assertUnsupported(Triple... triples) {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> generator.generateIri(base, graphOf(triples)))
        .withMessageStartingWith(UNSUPPORTED_TERM);
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

  /** A blank node whose {@code uniqueReference()} is null. */
  private static final class ForeignBlankNode implements BlankNode {

    @Override
    public String uniqueReference() {
      return null;
    }

    @Override
    public String ntriplesString() {
      return "foreign";
    }
  }

  /** A literal implementation that breaks the term contract in the way the test needs. */
  private record ForeignLiteral(String lexicalForm, Optional<String> languageTag, IRI datatype) implements Literal {

    @Override
    public String ntriplesString() {
      return "foreign";
    }

    @Override
    public String getLexicalForm() {
      return lexicalForm;
    }

    @Override
    public IRI getDatatype() {
      return datatype;
    }

    @Override
    public Optional<String> getLanguageTag() {
      return languageTag;
    }
  }
}
