// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Unit tests for {@link BlankNodeCore}, the resource limit criterion of ni-rdf/1 §4.3.
 *
 * <p>The core counts the blank nodes RDFC-1.0 can only tell apart by permuting several mutually
 * indistinguishable neighbours. Large but harmless structures must count zero; dense symmetric
 * ones count every node.</p>
 */
class BlankNodeCoreTest {

  private static final String EX = "http://example.org/";

  private final RDF rdf = new SimpleRdf();
  private final IRI root = rdf.createIRI(EX + "a");
  private final IRI member = rdf.createIRI(EX + "member");

  @Test
  @DisplayName("a complete graph of 6 blank nodes has a core of 6, within the limit")
  void cliqueOfSix() {
    assertThat(BlankNodeCore.size(clique(6))).isEqualTo(6).isLessThanOrEqualTo(RdfDatasetCanonicalizer.MAX_CORE_SIZE);
  }

  @Test
  @DisplayName("a complete graph of 7 blank nodes has a core of 7, beyond the limit")
  void cliqueOfSeven() {
    assertThat(BlankNodeCore.size(clique(7))).isEqualTo(7).isGreaterThan(RdfDatasetCanonicalizer.MAX_CORE_SIZE);
  }

  @Test
  @DisplayName("an RDF list of equal values has an empty core, however long")
  void listOfEqualValues() {
    IRI first = rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#first");
    IRI rest = rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#rest");
    List<Triple> triples = new ArrayList<>();
    List<BlankNode> cells = nodes("l", 30);
    triples.add(rdf.createTriple(root, member, cells.getFirst()));
    for (int i = 0; i < cells.size(); i++) {
      triples.add(rdf.createTriple(cells.get(i), first, rdf.createLiteral("5")));
      triples.add(rdf.createTriple(cells.get(i), rest,
          i + 1 < cells.size() ? cells.get(i + 1) : rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#nil")));
    }

    assertThat(BlankNodeCore.size(triples)).isZero();
  }

  @Test
  @DisplayName("a directed cycle of 8 indistinguishable blank nodes has an empty core")
  void directedCycle() {
    IRI next = rdf.createIRI(EX + "next");
    List<BlankNode> cycle = nodes("c", 8);
    List<Triple> triples = new ArrayList<>(rootedAt(cycle));
    for (int i = 0; i < cycle.size(); i++) {
      triples.add(rdf.createTriple(cycle.get(i), next, cycle.get((i + 1) % cycle.size())));
    }

    assertThat(BlankNodeCore.size(triples)).isZero();
  }

  @Test
  @DisplayName("a cycle of 8 blank nodes linked in both directions has a core of 8")
  void undirectedCycle() {
    IRI adjacent = rdf.createIRI(EX + "adjacent");
    List<BlankNode> cycle = nodes("c", 8);
    List<Triple> triples = new ArrayList<>(rootedAt(cycle));
    for (int i = 0; i < cycle.size(); i++) {
      triples.add(rdf.createTriple(cycle.get(i), adjacent, cycle.get((i + 1) % cycle.size())));
      triples.add(rdf.createTriple(cycle.get(i), adjacent, cycle.get((i + cycle.size() - 1) % cycle.size())));
    }

    assertThat(BlankNodeCore.size(triples)).isEqualTo(8);
  }

  @Test
  @DisplayName("blank nodes told apart by their own triples are not ambiguous at all")
  void distinguishableNodes() {
    IRI knows = rdf.createIRI(EX + "knows");
    IRI name = rdf.createIRI(EX + "name");
    List<BlankNode> nodes = nodes("n", 7);
    List<Triple> triples = new ArrayList<>(rootedAt(nodes));
    for (BlankNode from : nodes) {
      triples.add(rdf.createTriple(from, name, rdf.createLiteral(from.uniqueReference())));
      for (BlankNode to : nodes) {
        if (from != to) {
          triples.add(rdf.createTriple(from, knows, to));
        }
      }
    }

    assertThat(BlankNodeCore.size(triples)).as("a complete graph whose nodes carry distinct names").isZero();
  }

  /** {@code n} blank nodes hanging off the root, every ordered pair linked by one predicate. */
  private List<Triple> clique(int n) {
    IRI knows = rdf.createIRI(EX + "knows");
    List<BlankNode> nodes = nodes("n", n);
    List<Triple> triples = new ArrayList<>(rootedAt(nodes));
    for (BlankNode from : nodes) {
      for (BlankNode to : nodes) {
        if (from != to) {
          triples.add(rdf.createTriple(from, knows, to));
        }
      }
    }
    return triples;
  }

  private List<Triple> rootedAt(List<BlankNode> nodes) {
    return nodes.stream().map(node -> rdf.createTriple(root, member, node)).toList();
  }

  private List<BlankNode> nodes(String prefix, int n) {
    List<BlankNode> nodes = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      nodes.add(rdf.createBlankNode(prefix + i));
    }
    return nodes;
  }
}
