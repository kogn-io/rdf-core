// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.cid.CanonicalizationResourceLimitExceededException;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Unit tests for {@link RDFDatasetCanonicalizer}: RDFC-1.0 labels that depend on structure, not
 * on input labels, the canonical N-Quads escaping the labels hang on, and the resource limit
 * checked before RDFC-1.0 runs.
 */
class RDFDatasetCanonicalizerTest {

  private static final String EX = "http://example.org/";

  private final RDFDatasetCanonicalizer canonicalizer = new RDFDatasetCanonicalizer();
  private final RDF rdf = new SimpleRdf();

  @Test
  @DisplayName("every blank node gets a canonical issued identifier c14n0, c14n1, … without the _: prefix")
  void everyBlankNodeIsLabelled() {
    Map<String, String> labels = canonicalizer.canonicalIdentifiers(chain("table", "entry"));

    assertThat(labels).containsOnlyKeys("table", "entry");
    assertThat(labels.values()).containsExactlyInAnyOrder("c14n0", "c14n1");
  }

  @Test
  @DisplayName("the same structure gets the same labels, whatever the input labels")
  void labelsDependOnStructureOnly() {
    Map<String, String> first = canonicalizer.canonicalIdentifiers(chain("table", "entry"));
    Map<String, String> second = canonicalizer.canonicalIdentifiers(chain("x9", "b0"));

    assertThat(second.get("x9")).isEqualTo(first.get("table"));
    assertThat(second.get("b0")).isEqualTo(first.get("entry"));
  }

  @Test
  @DisplayName("a graph without blank nodes has no labels")
  void noBlankNodes() {
    List<Triple> triples = List
        .of(rdf.createTriple(rdf.createIRI(EX + "a"), rdf.createIRI(EX + "p"), rdf.createLiteral("x")));

    assertThat(canonicalizer.canonicalIdentifiers(triples)).isEmpty();
  }

  @Test
  @DisplayName("control characters are escaped as canonical N-Quads before hashing: \"x\\t\" orders ahead of \"x\"")
  void controlCharactersAreEscapedTheRdfcWay() {
    // Canonical N-Quads writes the tab as the two characters \t. Hashed that way, the blank node
    // holding "x\t" gets the first label; hashed with a raw tab it would not.
    IRI p = rdf.createIRI(EX + "p");
    BlankNode tab = rdf.createBlankNode("tab");
    BlankNode plain = rdf.createBlankNode("plain");
    List<Triple> triples = List.of(rdf.createTriple(tab, p, rdf.createLiteral("x\t")),
        rdf.createTriple(plain, p, rdf.createLiteral("x")));

    Map<String, String> labels = canonicalizer.canonicalIdentifiers(triples);

    assertThat(labels).containsEntry("tab", "c14n0").containsEntry("plain", "c14n1");
  }

  @Test
  @DisplayName("a complete graph of 3 blank nodes is canonicalized")
  void cliqueOfThreeIsCanonicalized() {
    assertThat(canonicalizer.canonicalIdentifiers(clique(3))).hasSize(3);
  }

  @Test
  @DisplayName("a complete graph of 4 blank nodes is already rejected before RDFC-1.0 runs (3 still pass)")
  void cliqueOfFourIsRejected() {
    assertThatExceptionOfType(CanonicalizationResourceLimitExceededException.class)
        .isThrownBy(() -> canonicalizer.canonicalIdentifiers(clique(4)))
        .withMessageStartingWith("[RESOURCE_LIMIT]")
        .withNoCause();
  }

  private static final String SUPPLEMENTARY = "\uD83D\uDE00";
  private static final String UPPER_BMP = "\uE000";

  @Test
  @DisplayName("a graph with blank nodes that mixes a supplementary and an E000-FFFF code point is canonicalized")
  void mixedCodePointRangesWithBlankNodesAreCanonicalized() {
    // The labels themselves are pinned by the ni-rdf/1 vector code-point-order, whose two blank
    // nodes differ only in such literals: UTF-16 code unit order would swap them.
    assertThat(canonicalizer.canonicalIdentifiers(withLiterals(SUPPLEMENTARY, UPPER_BMP))).hasSize(1);
  }

  private List<Triple> withLiterals(String first, String second) {
    IRI p = rdf.createIRI(EX + "p");
    BlankNode subject = rdf.createBlankNode("b");
    return List.of(rdf.createTriple(subject, p, rdf.createLiteral(first)),
        rdf.createTriple(subject, p, rdf.createLiteral(second)));
  }

  private List<Triple> chain(String tableLabel, String entryLabel) {
    IRI resource = rdf.createIRI(EX + "resource");
    BlankNode table = rdf.createBlankNode(tableLabel);
    BlankNode entry = rdf.createBlankNode(entryLabel);
    return List.of(rdf.createTriple(resource, rdf.createIRI(EX + "hasTable"), table),
        rdf.createTriple(table, rdf.createIRI(EX + "hasEntry"), entry),
        rdf.createTriple(entry, rdf.createIRI(EX + "hasValue"), rdf.createLiteral("100")));
  }

  private List<Triple> clique(int n) {
    IRI root = rdf.createIRI(EX + "a");
    IRI knows = rdf.createIRI(EX + "knows");
    List<BlankNode> nodes = new ArrayList<>();
    List<Triple> triples = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      BlankNode node = rdf.createBlankNode("n" + i);
      nodes.add(node);
      triples.add(rdf.createTriple(root, rdf.createIRI(EX + "member"), node));
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
}
