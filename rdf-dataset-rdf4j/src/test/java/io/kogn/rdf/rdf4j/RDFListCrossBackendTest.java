// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFList;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabRdf;

/**
 * Guards that {@code createRDFList} yields the same triple set on both backends, up to the
 * identity of the freshly minted blank nodes: the bare rdf:first/rdf:rest chain terminated by
 * rdf:nil, with no rdf:type triple.
 */
class RDFListCrossBackendTest {

  private final RDF simple = new SimpleRdf();

  private final RDF rdf4j = new RDF4JFactory();

  @Test
  @DisplayName("both backends build the same list structure and the same number of triples")
  void sameStructureOnBothBackends() {
    for (int size = 1; size <= 4; size++) {
      List<RDFTerm> items = new ArrayList<>();
      for (int i = 0; i < size; i++) {
        items.add(simple.createIRI("http://example.org/item" + i));
      }

      Shape fromSimple = shapeOf(simple.createRDFList(items));
      Shape fromRdf4j = shapeOf(rdf4j.createRDFList(items));

      assertThat(fromRdf4j).isEqualTo(fromSimple);
      assertThat(fromRdf4j.tripleCount()).isEqualTo(2 * size);
      assertThat(fromRdf4j.predicates()).containsOnly(VocabRdf.FIRST, VocabRdf.REST);
    }
  }

  @Test
  @DisplayName("both backends return the empty list for no items")
  void emptyListOnBothBackends() {
    assertThat(rdf4j.createRDFList(List.of()).hasItems()).isFalse();
    assertThat(simple.createRDFList(List.of()).hasItems()).isFalse();
  }

  /** Walks the chain from the head; blank-node identity is dropped, only structure remains. */
  private static Shape shapeOf(final RDFList list) {
    ReadableGraph graph = list.graph();
    List<RDFTerm> firsts = new ArrayList<>();
    RDFTerm node = list.head();
    while (!node.equals(VocabRdf.NIL)) {
      BlankNode current = (BlankNode) node;
      firsts.add(graph.stream(current, VocabRdf.FIRST, null).map(Triple::getObject).findFirst().orElseThrow());
      node = graph.stream(current, VocabRdf.REST, null).map(Triple::getObject).findFirst().orElseThrow();
    }
    List<IRI> predicates = graph.stream().map(Triple::getPredicate).distinct().toList();
    return new Shape(firsts, graph.size(), predicates);
  }

  private record Shape(List<RDFTerm> firsts, long tripleCount, List<IRI> predicates) {
    Shape {
      predicates = predicates.stream().sorted((x, y) -> x.getIRIString().compareTo(y.getIRIString())).toList();
    }
  }
}
