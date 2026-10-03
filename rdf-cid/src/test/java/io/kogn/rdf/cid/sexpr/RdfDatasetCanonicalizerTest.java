// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.cid.CanonicalizationResourceLimitExceededException;
import io.kogn.rdf.cid.ContentAddressingException;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Unit tests for {@link RdfDatasetCanonicalizer}: RDFC-1.0 labels that depend on structure, not
 * on input labels, the canonical N-Quads escaping the labels hang on, and the resource limit
 * checked before RDFC-1.0 runs.
 */
class RdfDatasetCanonicalizerTest {

  private static final String EX = "http://example.org/";

  private final RdfDatasetCanonicalizer canonicalizer = new RdfDatasetCanonicalizer();
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
  @DisplayName("a complete graph of 7 blank nodes is rejected before RDFC-1.0 runs")
  void cliqueOfSevenIsRejected() {
    assertThatExceptionOfType(CanonicalizationResourceLimitExceededException.class)
        .isThrownBy(() -> canonicalizer.canonicalIdentifiers(clique(7)))
        .withMessageStartingWith("[RESOURCE_LIMIT]")
        .withNoCause();
  }

  private static final String SUPPLEMENTARY = "\uD83D\uDE00";
  private static final String PRIVATE_USE_BMP = "\uFFFD";

  @Test
  @DisplayName("a supplementary and an E000-FFFF code point in a graph with blank nodes is rejected (titanium-rdf-canon#65)")
  void mixedCodePointRangesWithBlankNodesAreRejected() {
    List<Triple> triples = withLiterals(true, SUPPLEMENTARY, PRIVATE_USE_BMP);

    assertThatThrownBy(() -> canonicalizer.canonicalIdentifiers(triples))
        .isExactlyInstanceOf(ContentAddressingException.class)
        .hasMessageContaining("titanium-rdf-canon#65");
  }

  @Test
  @DisplayName("the two ranges may be spread over different terms, an IRI included")
  void mixedRangesAcrossTermsAreRejected() {
    IRI p = rdf.createIRI(EX + "p" + PRIVATE_USE_BMP);
    List<Triple> triples = List.of(rdf.createTriple(rdf.createBlankNode("b"), p, rdf.createLiteral(SUPPLEMENTARY)));

    assertThatThrownBy(() -> canonicalizer.canonicalIdentifiers(triples))
        .isExactlyInstanceOf(ContentAddressingException.class)
        .hasMessageContaining("titanium-rdf-canon#65");
  }

  @Test
  @DisplayName("only supplementary code points are canonicalized")
  void onlySupplementaryIsAccepted() {
    assertThat(canonicalizer.canonicalIdentifiers(withLiterals(true, SUPPLEMENTARY, SUPPLEMENTARY))).hasSize(1);
  }

  @Test
  @DisplayName("only E000-FFFF code points are canonicalized")
  void onlyUpperBmpIsAccepted() {
    assertThat(canonicalizer.canonicalIdentifiers(withLiterals(true, PRIVATE_USE_BMP, PRIVATE_USE_BMP))).hasSize(1);
  }

  @Test
  @DisplayName("mixed ranges in a graph without blank nodes need no canonicalization and pass")
  void mixedRangesWithoutBlankNodesPass() {
    assertThat(canonicalizer.canonicalIdentifiers(withLiterals(false, SUPPLEMENTARY, PRIVATE_USE_BMP))).isEmpty();
  }

  private List<Triple> withLiterals(boolean blankSubject, String first, String second) {
    IRI p = rdf.createIRI(EX + "p");
    BlankNodeOrIRI subject = blankSubject ? rdf.createBlankNode("b") : rdf.createIRI(EX + "s");
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
