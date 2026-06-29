// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.vocab.VocabRdf;

class SimpleRdfRDFListTest {

  private final RDF rdf = new SimpleRdf();

  private final IRI a = rdf.createIRI("http://example.org/a");
  private final IRI b = rdf.createIRI("http://example.org/b");
  private final IRI c = rdf.createIRI("http://example.org/c");

  @Test
  void emptyListForNullItems() {
    RDFList list = rdf.createRDFList(null);

    assertThat(list.hasItems()).isFalse();
    assertThat(list.head()).isNull();
    assertThat(list.graph()).isNull();
  }

  @Test
  void emptyListForEmptyItems() {
    RDFList list = rdf.createRDFList(List.of());

    assertThat(list.hasItems()).isFalse();
  }

  @Test
  void buildsStandardRdfListStructure() {
    RDFList list = rdf.createRDFList(List.of(a, b, c));

    assertThat(list.hasItems()).isTrue();
    ReadableGraph graph = list.graph();
    // 3 first + 3 rest triples
    assertThat(graph.size()).isEqualTo(6);

    // head -> first a, rest -> node2
    BlankNode head = list.head();
    assertThat(graph.stream(head, VocabRdf.FIRST, null).map(Triple::getObject)).containsExactly(a);

    Triple restOfHead = graph.stream(head, VocabRdf.REST, null).findFirst().orElseThrow();
    BlankNodeOrIRI node2 = (BlankNodeOrIRI) restOfHead.getObject();
    assertThat(graph.stream(node2, VocabRdf.FIRST, null).map(Triple::getObject)).containsExactly(b);

    Triple restOfNode2 = graph.stream(node2, VocabRdf.REST, null).findFirst().orElseThrow();
    BlankNodeOrIRI node3 = (BlankNodeOrIRI) restOfNode2.getObject();
    assertThat(graph.stream(node3, VocabRdf.FIRST, null).map(Triple::getObject)).containsExactly(c);

    // last rest points to rdf:nil
    assertThat(graph.stream(node3, VocabRdf.REST, null).map(Triple::getObject)).containsExactly(VocabRdf.NIL);
  }

  @Test
  void singleItemListTerminatesWithNil() {
    RDFList list = rdf.createRDFList(List.of(a));

    ReadableGraph graph = list.graph();
    assertThat(graph.size()).isEqualTo(2);
    assertThat(graph.stream(list.head(), VocabRdf.FIRST, null).map(Triple::getObject)).containsExactly(a);
    assertThat(graph.stream(list.head(), VocabRdf.REST, null).map(Triple::getObject)).containsExactly(VocabRdf.NIL);
  }
}
