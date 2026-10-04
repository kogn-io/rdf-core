// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.apicatalog.rdf.canon.RdfCanon;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Unit tests for {@link CanonicalizationCost}, the resource limit criterion of ni-rdf/1 §4.3.
 *
 * <p>The cost bound is high for blank nodes RDFC-1.0 can only tell apart by trying many orders
 * of mutually indistinguishable neighbours. Large but harmless structures must stay far below
 * the limit; the figures asserted here are the ones listed in the specification's vectors.</p>
 */
class CanonicalizationCostTest {

  private static final String EX = "http://example.org/";

  private final RDF rdf = new SimpleRdf();
  private final IRI root = rdf.createIRI(EX + "a");
  private final IRI member = rdf.createIRI(EX + "member");

  @Test
  @DisplayName("a complete graph of 3 blank nodes costs 3456, within the limit")
  void cliqueOfThree() {
    assertThat(cost(clique(3))).isEqualTo(3_456L);
  }

  @Test
  @DisplayName("a complete graph of 6 blank nodes is beyond the limit")
  void cliqueOfSix() {
    assertThat(cost(clique(6))).isGreaterThan(RdfDatasetCanonicalizer.MAX_COST);
  }

  @Test
  @DisplayName("two blank nodes with 7 identical blank-node children each cost 5 806 080, with 8 they are beyond the limit")
  void twoHubs() {
    assertThat(cost(hubs(2, 7))).isEqualTo(5_806_080L);
    assertThat(cost(hubs(2, 8))).isEqualTo(65_318_400L);
  }

  @Test
  @DisplayName("an RDF list of equal values stays far below the limit, however long")
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

    assertThat(cost(triples)).isLessThan(100_000L);
  }

  @Test
  @DisplayName("a directed cycle of 200 blank nodes costs 8 080 000, one of 250 is beyond the limit")
  void directedCycle() {
    assertThat(cost(directedCycle(200))).isEqualTo(8_080_000L);
    assertThat(cost(directedCycle(250))).isEqualTo(15_750_000L);
  }

  @Test
  @DisplayName("a cycle of 7 blank nodes linked in both directions costs 8 028 160, one of 8 is beyond the limit")
  void undirectedCycle() {
    assertThat(cost(undirectedCycle(7))).isEqualTo(8_028_160L);
    assertThat(cost(undirectedCycle(8))).isGreaterThan(RdfDatasetCanonicalizer.MAX_COST);
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

    assertThat(cost(triples)).as("a complete graph whose nodes carry distinct names").isZero();
  }

  @Test
  @DisplayName("a triple whose subject and object are the same blank node counts once per position in its first-degree hash")
  void selfLoopCountsOncePerPosition() {
    // SHA-256 over the sorted lines "<…/a> <…/member> _:a .", "_:a <…/knows> _:a .", "_:a <…/knows> _:a ."
    assertThat(CanonicalizationCost.firstDegreeHashes(selfLoopBesideSecondBlankNode())).containsEntry("_:x",
        "83d8650896984a4842dab9daa172c044188861cb634f9e90c6ca13c910c9f0d2");
  }

  @Test
  @DisplayName("the first-degree hashes of the estimate are the ones titanium-rdfc labels by")
  void firstDegreeHashesMatchTitanium() throws ReflectiveOperationException {
    List<Triple> triples = selfLoopBesideSecondBlankNode();
    RdfCanon canon = RdfCanon.create("SHA-256");
    for (Triple triple : triples) {
      if (triple.getObject() instanceof Literal literal) {
        canon.quad(Terms.resource(triple.getSubject()), triple.getPredicate().getIRIString(), literal.getLexicalForm(),
            Terms.datatypeOf(literal), null, null, null);
      } else {
        canon.quad(Terms.resource(triple.getSubject()), triple.getPredicate().getIRIString(),
            Terms.resource(triple.getObject()), null, null, null, null);
      }
    }
    // titanium-rdfc 3.0.0 keeps its Hash First Degree Quads package-private.
    Method hashFirstDegree = RdfCanon.class.getDeclaredMethod("hashFirstDegree", String.class);
    hashFirstDegree.setAccessible(true);

    Map<String, String> estimated = CanonicalizationCost.firstDegreeHashes(triples);

    assertThat(estimated).containsOnlyKeys("_:x", "_:y");
    for (Map.Entry<String, String> entry : estimated.entrySet()) {
      assertThat(hashFirstDegree.invoke(canon, entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
    }
  }

  /** The scenario of the vector {@code self-loop-beside-second-blank-node}, base not mapped out. */
  private List<Triple> selfLoopBesideSecondBlankNode() {
    BlankNode x = rdf.createBlankNode("x");
    BlankNode y = rdf.createBlankNode("y");
    return List.of(rdf.createTriple(root, member, x), rdf.createTriple(x, rdf.createIRI(EX + "knows"), x),
        rdf.createTriple(root, member, y), rdf.createTriple(y, rdf.createIRI(EX + "q"), rdf.createLiteral("v1")));
  }

  private long cost(List<Triple> triples) {
    return CanonicalizationCost.estimate(triples, RdfDatasetCanonicalizer.MAX_COST)
        .min(BigInteger.valueOf(Long.MAX_VALUE))
        .longValueExact();
  }

  private List<Triple> directedCycle(int n) {
    IRI next = rdf.createIRI(EX + "next");
    List<BlankNode> cycle = nodes("c", n);
    List<Triple> triples = new ArrayList<>(rootedAt(cycle));
    for (int i = 0; i < n; i++) {
      triples.add(rdf.createTriple(cycle.get(i), next, cycle.get((i + 1) % n)));
    }
    return triples;
  }

  private List<Triple> undirectedCycle(int n) {
    IRI adjacent = rdf.createIRI(EX + "adjacent");
    List<BlankNode> cycle = nodes("c", n);
    List<Triple> triples = new ArrayList<>(rootedAt(cycle));
    for (int i = 0; i < n; i++) {
      triples.add(rdf.createTriple(cycle.get(i), adjacent, cycle.get((i + 1) % n)));
      triples.add(rdf.createTriple(cycle.get(i), adjacent, cycle.get((i + n - 1) % n)));
    }
    return triples;
  }

  /** {@code hubs} blank nodes hanging off the root, each with {@code leaves} identical blank-node children. */
  private List<Triple> hubs(int hubs, int leaves) {
    IRI item = rdf.createIRI(EX + "item");
    List<BlankNode> hubNodes = nodes("h", hubs);
    List<Triple> triples = new ArrayList<>(rootedAt(hubNodes));
    for (int h = 0; h < hubs; h++) {
      for (BlankNode leaf : nodes("l" + h + "_", leaves)) {
        triples.add(rdf.createTriple(hubNodes.get(h), item, leaf));
      }
    }
    return triples;
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
