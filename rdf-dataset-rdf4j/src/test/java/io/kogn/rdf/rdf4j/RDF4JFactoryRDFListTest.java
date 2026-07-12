// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFList;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabRdf;

/**
 * Verifies {@link RDF4JFactory#createRDFList(List)} builds a correct RDF-1.1 collection
 * (rdf:first/rdf:rest chain, terminated by rdf:nil) for a term list mixing IRI, Literal and
 * BlankNode, both when all terms originate from the same {@link RDF4JFactory} and when they are
 * mixed with terms from a foreign {@link RDF} implementation ({@link SimpleRdf}).
 */
class RDF4JFactoryRDFListTest {

  private final RDF rdf = new RDF4JFactory();

  private final IRI a = rdf.createIRI("http://example.org/a");

  // RDFCollections.asRDF marks the head node with rdf:type rdf:List; not exposed via VocabRdf.
  private final IRI rdfList = rdf.createIRI(VocabRdf.NAMESPACE + "List");

  @Test
  @DisplayName("createRDFList builds rdf:first/rdf:rest chain for a mixed-type term list (all terms RDF4J-native)")
  void buildsRdfListWithMixedTermTypes() {
    // given
    Literal literal = rdf.createLiteral("hello", "en");
    BlankNode blankNode = rdf.createBlankNode("b1");
    List<RDFTerm> items = List.of(a, literal, blankNode);

    // when
    RDFList list = rdf.createRDFList(items);

    // then
    assertThat(list.hasItems()).isTrue();
    ReadableGraph graph = list.graph();
    // 3 first + 3 rest + 1 rdf:type List (added by RDFCollections.asRDF on the head node)
    assertThat(graph.size()).isEqualTo(7);

    BlankNode head = list.head();
    assertThat(graph.stream(head, VocabRdf.TYPE, null).map(Triple::getObject)).containsExactly(rdfList);
    assertThat(graph.stream(head, VocabRdf.FIRST, null).map(Triple::getObject)).containsExactly(a);

    Triple restOfHead = graph.stream(head, VocabRdf.REST, null).findFirst().orElseThrow();
    BlankNodeOrIRI node2 = (BlankNodeOrIRI) restOfHead.getObject();
    assertThat(graph.stream(node2, VocabRdf.FIRST, null).map(Triple::getObject)).containsExactly(literal);

    Triple restOfNode2 = graph.stream(node2, VocabRdf.REST, null).findFirst().orElseThrow();
    BlankNodeOrIRI node3 = (BlankNodeOrIRI) restOfNode2.getObject();
    assertThat(graph.stream(node3, VocabRdf.FIRST, null).map(Triple::getObject)).containsExactly(blankNode);

    // last rest points to rdf:nil
    assertThat(graph.stream(node3, VocabRdf.REST, null).map(Triple::getObject)).containsExactly(VocabRdf.NIL);
  }

  @Test
  @DisplayName("createRDFList accepts a mixed-type term list built from a foreign RDF implementation (SimpleRdf)")
  void buildsRdfListFromCrossImplementationTerms() {
    // given
    SimpleRdf simple = new SimpleRdf();
    IRI iri = simple.createIRI("http://example.org/x");
    Literal literal = simple.createLiteral("world", "de");
    BlankNode blankNode = simple.createBlankNode("cross-b1");
    List<RDFTerm> items = List.of(iri, literal, blankNode);

    // when
    RDFList list = rdf.createRDFList(items);

    // then
    assertThat(list.hasItems()).isTrue();
    ReadableGraph graph = list.graph();
    // 3 first + 3 rest + 1 rdf:type List (added by RDFCollections.asRDF on the head node)
    assertThat(graph.size()).isEqualTo(7);

    BlankNode head = list.head();
    assertThat(graph.stream(head, VocabRdf.TYPE, null).map(Triple::getObject)).containsExactly(rdfList);
    assertThat(graph.stream(head, VocabRdf.FIRST, null).map(Triple::getObject)).containsExactly(iri);

    Triple restOfHead = graph.stream(head, VocabRdf.REST, null).findFirst().orElseThrow();
    BlankNodeOrIRI node2 = (BlankNodeOrIRI) restOfHead.getObject();
    assertThat(graph.stream(node2, VocabRdf.FIRST, null).map(Triple::getObject)).containsExactly(literal);

    Triple restOfNode2 = graph.stream(node2, VocabRdf.REST, null).findFirst().orElseThrow();
    BlankNodeOrIRI node3 = (BlankNodeOrIRI) restOfNode2.getObject();
    assertThat(graph.stream(node3, VocabRdf.FIRST, null).map(Triple::getObject)).containsExactly(blankNode);

    // last rest points to rdf:nil
    assertThat(graph.stream(node3, VocabRdf.REST, null).map(Triple::getObject)).containsExactly(VocabRdf.NIL);
  }
}
