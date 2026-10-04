// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.cid.CanonicalizationResourceLimitExceededException;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * The resource limit of ni-rdf/1 section 4.3 end to end: the cost bound {@code E} of small graphs
 * worked out by hand from the formula {@code W_C * |C| * (|C|^2 + T_C)}, summed over components,
 * independent of input order and blank node labels, with the limit decided on the <em>mapped</em>
 * graph (section 4.1, section 4.2) through {@link ContentAddressableRdfSerializer}.
 */
class ResourceLimitEndToEndTest {

  private static final String EX = "http://example.org/";
  private static final long LIMIT = 10_000_000L;

  private final RDF rdf = new SimpleRdf();
  private final IRI root = rdf.createIRI(EX + "doc");
  private final IRI member = rdf.createIRI(EX + "member");
  private final ContentAddressableRdfSerializer serializer = new ContentAddressableRdfSerializer(
      new RdfDatasetCanonicalizer(), rdf);

  @Test
  @DisplayName("T_C counts a triple once even if subject and object are both in C")
  void tripleBetweenTwoNodesOfTheComponentCountsOnce() {
    // a and b point at each other with one predicate: indistinguishable, one component.
    // |C| = 2, W = 1 (each has one related node per group), T = root->a, root->b, a->b, b->a = 4
    // E = 1 * 2 * (4 + 4) = 16
    assertThat(cost(pair("q", "a", "b", false))).isEqualTo(16L);
  }

  @Test
  @DisplayName("T_C counts the triples to blank neighbours that are not ambiguous")
  void nonAmbiguousBlankNeighboursCount() {
    // As above, but a and b each have a child with its own literal: the children are told apart,
    // so not ambiguous, yet their triples a->ca and b->cb mention a and b: T = 6, E = 2 * (4 + 6) = 20
    assertThat(cost(pair("q", "a", "b", true))).isEqualTo(20L);
  }

  @Test
  @DisplayName("E is the sum over the components, not the largest one")
  void sumOverComponents() {
    List<Triple> triples = new ArrayList<>(pair("q", "a", "b", false));
    triples.addAll(clique(3, "k"));

    // 16 for the pair, 3456 for the complete graph of three (|C| = 3, W = 4^3, T = 3 + 6 = 9)
    assertThat(cost(triples)).isEqualTo(16L + 3_456L);
  }

  @Test
  @DisplayName("E depends neither on triple order nor on blank node labels")
  void orderAndLabelsDoNotMatter() {
    List<Triple> triples = new ArrayList<>(pair("q", "a", "b", true));
    triples.addAll(clique(3, "k"));
    BigInteger expected = BigInteger.valueOf(20L + 3_456L);

    for (long seed = 1; seed <= 5; seed++) {
      List<Triple> shuffled = new ArrayList<>(relabelled(triples, "seed" + seed + "_"));
      Collections.shuffle(shuffled, new Random(seed));
      assertThat(CanonicalizationCost.estimate(shuffled, LIMIT)).as("seed %d", seed).isEqualTo(expected);
    }
  }

  @Test
  @DisplayName("the boundary: 35 hubs of 6 identical children (E = 9 878 400) pass, 36 (E = 10 160 640) are rejected")
  void boundary() {
    assertThat(CanonicalizationCost.estimate(hubs(35, 6), LIMIT)).isEqualTo(BigInteger.valueOf(9_878_400L));
    assertThat(CanonicalizationCost.estimate(hubs(36, 6), LIMIT)).isEqualTo(BigInteger.valueOf(10_160_640L));

    assertThatNoException().isThrownBy(() -> serializer.serializeWithIri(root, hubs(35, 6)));
    assertThatExceptionOfType(CanonicalizationResourceLimitExceededException.class)
        .isThrownBy(() -> serializer.serializeWithIri(root, hubs(36, 6)))
        .withMessageStartingWith("[RESOURCE_LIMIT]");
  }

  @Test
  @DisplayName("section 4.2: \"v\"@EN and \"v\"@en on a blank node are one triple before the cost is estimated")
  void caseVariantsCollapseBeforeTheEstimate() {
    // 2 hubs with 8 children each, every child holding one literal: 4 per hub tagged EN, 4 tagged en.
    List<Triple> raw = hubsWithTaggedChildren("EN", "en");
    List<Triple> lowerCased = hubsWithTaggedChildren("en", "en");

    // Taken as written the children split into two groups of four (hub orders 4! * 4!):
    assertThat(CanonicalizationCost.estimate(raw, LIMIT)).isEqualTo(BigInteger.valueOf(1_016_064L));
    // Mapped (section 4.1) they are eight identical children (hub orders 8!, |C| = 9, T = 17): 2 * 40320 * 9 * (81 +
    // 17)
    assertThat(CanonicalizationCost.estimate(lowerCased, LIMIT)).isEqualTo(BigInteger.valueOf(71_124_480L));

    assertThatExceptionOfType(CanonicalizationResourceLimitExceededException.class)
        .isThrownBy(() -> serializer.serializeWithIri(root, raw))
        .withMessageStartingWith("[RESOURCE_LIMIT]");
  }

  @Test
  @DisplayName("section 4.2: triples that become equal through lower-casing count once in T_C")
  void collapsedDuplicatesCountOnceInTheEstimate() {
    // 35 hubs of 6 children (E = 9 878 400); each of the first 24 hubs also holds "v"@EN and "v"@en.
    // Collapsed: one extra triple per such hub, 24 * 7 * 720 = 120 960 more: E = 9 999 360, within the limit.
    // Counted twice it would be 24 * 2 * 7 * 720 = 241 920 more: 10 120 320, beyond it.
    List<Triple> triples = new ArrayList<>(hubs(35, 6));
    IRI label = rdf.createIRI(EX + "label");
    for (int hub = 0; hub < 24; hub++) {
      BlankNode hubNode = rdf.createBlankNode("h" + hub);
      triples.add(rdf.createTriple(hubNode, label, rdf.createLiteral("v", "EN")));
      triples.add(rdf.createTriple(hubNode, label, rdf.createLiteral("v", "en")));
    }

    assertThatNoException().isThrownBy(() -> serializer.serializeWithIri(root, triples));
  }

  @Test
  @DisplayName("section 4.2: the two spellings of a tag on a blank node hash like the one triple")
  void collapsedTriplesHashLikeOne() {
    BlankNode child = rdf.createBlankNode("c");
    IRI label = rdf.createIRI(EX + "label");
    List<Triple> both = List.of(rdf.createTriple(root, member, child),
        rdf.createTriple(child, label, rdf.createLiteral("v", "EN")),
        rdf.createTriple(child, label, rdf.createLiteral("v", "en")));
    List<Triple> one = List.of(rdf.createTriple(root, member, child),
        rdf.createTriple(child, label, rdf.createLiteral("v", "en")));

    assertThat(serializer.serializeWithIri(root, both).sexprBytes())
        .isEqualTo(serializer.serializeWithIri(root, one).sexprBytes());
  }

  private long cost(List<Triple> triples) {
    return CanonicalizationCost.estimate(triples, LIMIT).longValueExact();
  }

  /**
   * Two blank nodes under the root linking each other with {@code predicate}; with
   * {@code children}, each also has a child told apart by its own literal.
   */
  private List<Triple> pair(String predicate, String first, String second, boolean children) {
    IRI link = rdf.createIRI(EX + predicate);
    BlankNode a = rdf.createBlankNode(first);
    BlankNode b = rdf.createBlankNode(second);
    List<Triple> triples = new ArrayList<>();
    triples.add(rdf.createTriple(root, member, a));
    triples.add(rdf.createTriple(root, member, b));
    triples.add(rdf.createTriple(a, link, b));
    triples.add(rdf.createTriple(b, link, a));
    if (children) {
      IRI child = rdf.createIRI(EX + "child");
      IRI name = rdf.createIRI(EX + "name");
      BlankNode ca = rdf.createBlankNode(first + "_child");
      BlankNode cb = rdf.createBlankNode(second + "_child");
      triples.add(rdf.createTriple(a, child, ca));
      triples.add(rdf.createTriple(b, child, cb));
      triples.add(rdf.createTriple(ca, name, rdf.createLiteral("x")));
      triples.add(rdf.createTriple(cb, name, rdf.createLiteral("y")));
    }
    return triples;
  }

  private List<Triple> clique(int n, String prefix) {
    IRI knows = rdf.createIRI(EX + "knows");
    List<BlankNode> nodes = new ArrayList<>();
    List<Triple> triples = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      BlankNode node = rdf.createBlankNode(prefix + i);
      nodes.add(node);
      triples.add(rdf.createTriple(root, member, node));
    }
    for (BlankNode from : nodes) {
      for (BlankNode to : nodes) {
        if (from != to) {
          triples.add(rdf.createTriple(from, knows, to));
        }
      }
    }
    return triples;
  }

  /** {@code hubs} blank nodes under the root, each with {@code leaves} identical blank node children. */
  private List<Triple> hubs(int hubs, int leaves) {
    IRI item = rdf.createIRI(EX + "item");
    List<Triple> triples = new ArrayList<>();
    for (int h = 0; h < hubs; h++) {
      BlankNode hub = rdf.createBlankNode("h" + h);
      triples.add(rdf.createTriple(root, member, hub));
      for (int l = 0; l < leaves; l++) {
        triples.add(rdf.createTriple(hub, item, rdf.createBlankNode("l" + h + "_" + l)));
      }
    }
    return triples;
  }

  private List<Triple> hubsWithTaggedChildren(String firstHalfTag, String secondHalfTag) {
    IRI item = rdf.createIRI(EX + "item");
    IRI label = rdf.createIRI(EX + "label");
    List<Triple> triples = new ArrayList<>();
    for (int h = 0; h < 2; h++) {
      BlankNode hub = rdf.createBlankNode("h" + h);
      triples.add(rdf.createTriple(root, member, hub));
      for (int l = 0; l < 8; l++) {
        BlankNode child = rdf.createBlankNode("l" + h + "_" + l);
        triples.add(rdf.createTriple(hub, item, child));
        triples.add(rdf.createTriple(child, label, rdf.createLiteral("v", l < 4 ? firstHalfTag : secondHalfTag)));
      }
    }
    return triples;
  }

  private List<Triple> relabelled(List<Triple> triples, String prefix) {
    Map<String, BlankNode> renamed = new HashMap<>();
    List<Triple> result = new ArrayList<>();
    for (Triple triple : triples) {
      result.add(rdf.createTriple((BlankNodeOrIRI) relabel(triple.getSubject(), prefix, renamed), triple.getPredicate(),
          relabel(triple.getObject(), prefix, renamed)));
    }
    return result;
  }

  private RDFTerm relabel(RDFTerm term, String prefix, Map<String, BlankNode> renamed) {
    if (term instanceof BlankNode blankNode) {
      return renamed.computeIfAbsent(blankNode.uniqueReference(), key -> rdf.createBlankNode(prefix + renamed.size()));
    }
    return term;
  }
}
