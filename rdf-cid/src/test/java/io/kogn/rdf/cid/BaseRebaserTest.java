// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleIRI;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/** ni-rdf/1 §6 "Renaming": the base IRI and its fragment IRIs are replaced on terms, never on text. */
class BaseRebaserTest {

  private static final String EX = "http://example.org/";
  private static final String RESERVED = "urn:uuid:171650ec-eda4-47fd-9053-6a34696171c1";

  private RDF rdf;
  private ContentAddressedIriGenerator generator;
  private BaseRebaser rebaser;
  private IRI provisional;
  private IRI p;

  @BeforeEach
  void setUp() {
    rdf = new SimpleRdf();
    generator = new ContentAddressedIriGeneratorSexpr(rdf);
    rebaser = new BaseRebaser(rdf);
    provisional = rdf.createIRI(EX + "draft");
    p = rdf.createIRI(EX + "p");
  }

  @Test
  @DisplayName("the rebased graph verifies against the identifier it was named after")
  void rebasedGraphVerifiesAgainstItsIdentifier() {
    final IRI part = rdf.createIRI(EX + "draft#part");
    final IRI datatype = rdf.createIRI(EX + "draft#kind");
    final BlankNode node = rdf.createBlankNode();
    final Graph graph = rdf.createGraph();
    graph.add(provisional, p, rdf.createLiteral("v", "EN"));
    graph.add(provisional, p, part);
    graph.add(part, p, node);
    graph.add(node, p, rdf.createLiteral("1", datatype));
    graph.add(node, provisional, provisional);
    final IRI identifier = generator.generateIri(provisional, graph);

    final Graph rebased = rebaser.rebase(graph, provisional, identifier);

    assertThat(generator.generateIri(identifier, rebased)).isEqualTo(identifier);
    final IRI renamedPart = rdf.createIRI(identifier.getIRIString() + "#part");
    final IRI renamedDatatype = rdf.createIRI(identifier.getIRIString() + "#kind");
    assertThat(rebased.contains(rdf.createTriple(identifier, p, renamedPart))).isTrue();
    assertThat(rebased.contains(rdf.createTriple(identifier, p, rdf.createLiteral("v", "EN")))).isTrue();
    assertThat(rebased.contains(
        rdf.createTriple(rdf.createBlankNode(node.uniqueReference()), p, rdf.createLiteral("1", renamedDatatype))))
        .isTrue();
    assertThat(rebased.size()).isEqualTo(graph.size());
  }

  @Test
  @DisplayName("an IRI that merely starts with the old base is content, not a fragment IRI")
  void renamesTermsNotText() {
    final IRI longer = rdf.createIRI(EX + "draftsman");
    final IRI target = rdf.createIRI(EX + "final");
    final Graph graph = rdf.createGraph();
    graph.add(provisional, p, longer);
    graph.add(provisional, p, rdf.createLiteral(EX + "draft"));

    final Graph rebased = rebaser.rebase(graph, provisional, target);

    assertThat(rebased.contains(rdf.createTriple(target, p, longer))).isTrue();
    assertThat(rebased.contains(rdf.createTriple(target, p, rdf.createLiteral(EX + "draft")))).isTrue();
  }

  @Test
  @DisplayName("only the first # separates base and fragment; the fragment is carried over unchanged")
  void keepsFragmentAfterFirstHash() {
    final IRI target = rdf.createIRI("ni:///sha3-256;abc");
    final Graph graph = rdf.createGraph();
    graph.add(rdf.createIRI(EX + "draft#a#b"), p, rdf.createIRI(EX + "draft#"));

    final Graph rebased = rebaser.rebase(graph, provisional, target);

    assertThat(rebased
        .contains(rdf.createTriple(rdf.createIRI("ni:///sha3-256;abc#a#b"), p, rdf.createIRI("ni:///sha3-256;abc#"))))
        .isTrue();
  }

  @Test
  @DisplayName("rebasing onto the same base returns an equal graph")
  void sameBaseIsIdentity() {
    final Graph graph = rdf.createGraph();
    graph.add(provisional, p, rdf.createIRI(EX + "draft#x"));

    final Graph rebased = rebaser.rebase(graph, provisional, provisional);

    assertThat(rebased.stream()).containsExactlyInAnyOrderElementsOf(graph.stream().toList());
  }

  @Test
  @DisplayName("a base IRI with a # is rejected as [INVALID_BASE], old or new")
  void rejectsBaseWithFragment() {
    final Graph graph = rdf.createGraph();
    graph.add(provisional, p, provisional);
    final IRI withFragment = rdf.createIRI(EX + "x#f");

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graph, withFragment, provisional))
        .withMessageStartingWith("[INVALID_BASE]");
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graph, provisional, withFragment))
        .withMessageStartingWith("[INVALID_BASE]");
  }

  @Test
  @DisplayName("the reserved IRI is rejected as [RESERVED_IRI] as old or new base")
  void rejectsReservedBase() {
    final Graph graph = rdf.createGraph();
    graph.add(provisional, p, provisional);
    final IRI reserved = rdf.createIRI(RESERVED);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graph, reserved, provisional))
        .withMessageStartingWith("[RESERVED_IRI]");
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graph, provisional, reserved))
        .withMessageStartingWith("[RESERVED_IRI]");
  }

  @Test
  @DisplayName("a graph that already uses the new base would merge terms and is rejected")
  void rejectsCollisionWithNewBase() {
    final IRI target = rdf.createIRI(EX + "final");
    final Graph graph = rdf.createGraph();
    graph.add(provisional, p, rdf.createIRI(EX + "final#x"));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graph, provisional, target))
        .withMessageContaining(EX + "final#x");
  }

  @Test
  @DisplayName("null arguments are rejected")
  void rejectsNulls() {
    final Graph graph = rdf.createGraph();

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(null, provisional, provisional));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graph, null, provisional));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graph, provisional, null));
  }

  @Test
  @DisplayName("a graph using the new base IRI itself is rejected as well")
  void rejectsCollisionWithNewBaseItself() {
    final IRI target = rdf.createIRI(EX + "final");
    final Graph graph = rdf.createGraph();
    graph.add(provisional, p, target);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graph, provisional, target))
        .withMessageContaining("<" + EX + "final>");
  }

  @Test
  @DisplayName("a base that reads as a blank node label is rejected as [UNSUPPORTED_TERM]")
  void rejectsBlankNodeLikeBase() {
    final Graph graph = rdf.createGraph();
    graph.add(provisional, p, provisional);
    final IRI blankLike = new SimpleIRI("_:b0");

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graph, provisional, blankLike))
        .withMessageStartingWith("[UNSUPPORTED_TERM]");
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graph, blankLike, provisional))
        .withMessageStartingWith("[UNSUPPORTED_TERM]");
  }

  @Test
  @DisplayName("a null triple in a foreign graph is rejected as [UNSUPPORTED_TERM]")
  void rejectsNullTriple() {
    final ReadableGraph foreign = new ReadableGraph() {
      @Override
      public boolean contains(Triple triple) {
        return false;
      }

      @Override
      public long size() {
        return 1;
      }

      @Override
      public Stream<Triple> stream() {
        return Stream.of((Triple) null);
      }

      @Override
      public Stream<Triple> stream(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
        return stream();
      }

      @Override
      public boolean isEmpty() {
        return false;
      }
    };

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(foreign, provisional, rdf.createIRI(EX + "final")))
        .withMessageStartingWith("[UNSUPPORTED_TERM]");
  }

  @Test
  @DisplayName("the input graph is left unchanged")
  void leavesInputUnchanged() {
    final Graph graph = rdf.createGraph();
    graph.add(provisional, p, rdf.createIRI(EX + "draft#x"));
    final List<Triple> before = graph.stream().toList();

    rebaser.rebase(graph, provisional, rdf.createIRI(EX + "final"));

    assertThat(graph.stream().toList()).isEqualTo(before);
  }

  @Test
  @DisplayName("a base with a # is [INVALID_BASE] even if it also reads as a blank node label")
  void checksFragmentBeforeBlankNodeLabel() {
    final Graph graph = rdf.createGraph();
    graph.add(provisional, p, provisional);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graph, new SimpleIRI("_:x#y"), provisional))
        .withMessageStartingWith("[INVALID_BASE]");
  }

  @Test
  @DisplayName("a term of a foreign kind is rejected as [UNSUPPORTED_TERM]")
  void rejectsForeignTermKind() {
    final RDFTerm foreignKind = () -> "<foreign>";

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graphOf(tripleWithObject(foreignKind)), provisional, provisional))
        .withMessageStartingWith("[UNSUPPORTED_TERM]");
  }

  @Test
  @DisplayName("a null term in a foreign triple is rejected as [UNSUPPORTED_TERM]")
  void rejectsNullTerm() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> rebaser.rebase(graphOf(tripleWithObject(null)), provisional, provisional))
        .withMessageStartingWith("[UNSUPPORTED_TERM]");
  }

  private Triple tripleWithObject(final RDFTerm object) {
    return new Triple() {
      @Override
      public BlankNodeOrIRI getSubject() {
        return provisional;
      }

      @Override
      public IRI getPredicate() {
        return p;
      }

      @Override
      public RDFTerm getObject() {
        return object;
      }
    };
  }

  private static ReadableGraph graphOf(final Triple triple) {
    return new ReadableGraph() {
      @Override
      public boolean contains(Triple candidate) {
        return false;
      }

      @Override
      public long size() {
        return 1;
      }

      @Override
      public Stream<Triple> stream() {
        return Stream.of(triple);
      }

      @Override
      public Stream<Triple> stream(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
        return stream();
      }

      @Override
      public boolean isEmpty() {
        return false;
      }
    };
  }
}
