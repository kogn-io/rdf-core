// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabXsd;

/**
 * Unit test for {@link RdfDatasetCanonicalizer}.
 *
 * <p>Verifies that URDNA2015 canonicalization is deterministic: identical RDF
 * graphs with different BlankNode IDs must produce identical results after
 * canonicalization.</p>
 */
class RdfDatasetCanonicalizerTest {

  private RdfDatasetCanonicalizer canonicalizer;
  private RDF rdf;

  @BeforeEach
  void setUp() {
    canonicalizer = new RdfDatasetCanonicalizer();
    rdf = new SimpleRdf();
  }

  @Test
  void canonicalizationIsDeterministic() {
    // Create two identical graphs with different BlankNode IDs
    List<Triple> graph1 = createNutrientGraph("bn1", "bn2");
    List<Triple> graph2 = createNutrientGraph("xyz", "abc");

    // The BlankNode IDs should differ
    String bn1Graph1 = extractFirstBlankNodeId(graph1);
    String bn1Graph2 = extractFirstBlankNodeId(graph2);
    assertThat(bn1Graph1).as("BlankNode IDs should differ").isNotEqualTo(bn1Graph2);

    // Canonicalize both graphs
    Collection<Triple> canonical1 = canonicalizer.canonicalize(graph1);
    Collection<Triple> canonical2 = canonicalizer.canonicalize(graph2);

    // After canonicalization the BlankNode IDs should be identical
    String canonicalBn1 = extractFirstBlankNodeId(canonical1);
    String canonicalBn2 = extractFirstBlankNodeId(canonical2);
    assertThat(canonicalBn1).as("Canonicalized BlankNode IDs should be identical for identical graphs")
        .isEqualTo(canonicalBn2);

    // The serialized forms should be byte-identical
    String serialized1 = serializeToString(canonical1);
    String serialized2 = serializeToString(canonical2);
    assertThat(serialized1).as("Canonicalized graphs should serialize identically").isEqualTo(serialized2);
  }

  @Test
  void nestedBlankNodesCanonicalizeDeterministically() {
    // Test with nested BlankNodes (e.g. table -> entry -> quantity)
    List<Triple> graph1 = createNestedBlankNodeGraph("table1", "entry1", "value1");
    List<Triple> graph2 = createNestedBlankNodeGraph("xyz", "abc", "def");

    Collection<Triple> canonical1 = canonicalizer.canonicalize(graph1);
    Collection<Triple> canonical2 = canonicalizer.canonicalize(graph2);

    String serialized1 = serializeToString(canonical1);
    String serialized2 = serializeToString(canonical2);
    assertThat(serialized1).as("Nested BlankNodes should canonicalize deterministically").isEqualTo(serialized2);
  }

  @Test
  void literalsWithDatatypesArePreserved() {
    // Verify that datatype information is preserved
    IRI subject = rdf.createIRI("http://example.org/resource");
    IRI predicate = rdf.createIRI("http://example.org/value");

    List<Triple> graph = new ArrayList<>();
    graph.add(rdf.createTriple(subject, predicate,
        rdf.createLiteral("123.45", rdf.createIRI(VocabXsd.DECIMAL.getIRIString()))));

    Collection<Triple> canonical = canonicalizer.canonicalize(graph);

    // Verify that the Literal with its datatype is preserved
    assertThat(canonical).as("Graph should contain one triple").hasSize(1);
    Triple triple = canonical.iterator().next();

    io.kogn.rdf.terms.RDFTerm object = triple.getObject();
    assertThat(object).isInstanceOf(Literal.class);
    Literal lit = (Literal) object;
    assertThat(lit.getLexicalForm()).as("Lexical form should be preserved").isEqualTo("123.45");
    IRI xsdDecimal = rdf.createIRI(VocabXsd.DECIMAL.getIRIString());
    assertThat(lit.getDatatype()).as("Datatype should be preserved").isEqualTo(xsdDecimal);
  }

  @Test
  void literalsWithLanguageTagsArePreserved() {
    // The round trip through Titanium has a separate branch for language-tagged literals.
    // If it dropped the tag, "Bank"@en and "Bank"@de would reach the serializer as the same
    // literal and share an identifier — a collision the serializer could no longer prevent.
    IRI subject = rdf.createIRI("http://example.org/resource");
    IRI predicate = rdf.createIRI("http://example.org/label");

    List<Triple> graph = new ArrayList<>();
    graph.add(rdf.createTriple(subject, predicate, rdf.createLiteral("Bank", "en")));

    Collection<Triple> canonical = canonicalizer.canonicalize(graph);

    assertThat(canonical).as("Graph should contain one triple").hasSize(1);
    io.kogn.rdf.terms.RDFTerm object = canonical.iterator().next().getObject();
    assertThat(object).isInstanceOf(Literal.class);
    Literal lit = (Literal) object;
    assertThat(lit.getLexicalForm()).as("Lexical form should be preserved").isEqualTo("Bank");
    assertThat(lit.getLanguageTag()).as("Language tag should be preserved").isEqualTo(Optional.of("en"));
  }

  // Helper methods

  private List<Triple> createNutrientGraph(String blankNodeId1, String blankNodeId2) {
    List<Triple> triples = new ArrayList<>();

    IRI resource = rdf.createIRI("http://example.org/resource");
    IRI hasTable = rdf.createIRI("http://example.org/hasTable");
    IRI hasEntry = rdf.createIRI("http://example.org/hasEntry");
    IRI hasProperty = rdf.createIRI("http://example.org/hasProperty");
    IRI hasValue = rdf.createIRI("http://example.org/hasValue");

    BlankNode table = rdf.createBlankNode(blankNodeId1);
    BlankNode entry = rdf.createBlankNode(blankNodeId2);

    triples.add(rdf.createTriple(resource, hasTable, table));
    triples.add(rdf.createTriple(table, hasEntry, entry));
    triples.add(rdf.createTriple(entry, hasProperty, rdf.createIRI("http://example.org/measurement")));
    triples.add(rdf.createTriple(entry, hasValue, rdf.createLiteral("100")));

    return triples;
  }

  private List<Triple> createNestedBlankNodeGraph(String bn1, String bn2, String bn3) {
    List<Triple> triples = new ArrayList<>();

    IRI resource = rdf.createIRI("http://example.org/resource");
    IRI hasTable = rdf.createIRI("http://example.org/hasTable");
    IRI hasEntry = rdf.createIRI("http://example.org/hasEntry");
    IRI hasQuantity = rdf.createIRI("http://example.org/hasQuantity");
    IRI hasUnit = rdf.createIRI("http://example.org/hasUnit");
    IRI hasNumericValue = rdf.createIRI("http://example.org/hasNumericValue");

    BlankNode table = rdf.createBlankNode(bn1);
    BlankNode entry = rdf.createBlankNode(bn2);
    BlankNode quantity = rdf.createBlankNode(bn3);

    triples.add(rdf.createTriple(resource, hasTable, table));
    triples.add(rdf.createTriple(table, hasEntry, entry));
    triples.add(rdf.createTriple(entry, hasQuantity, quantity));
    triples.add(rdf.createTriple(quantity, hasUnit, rdf.createIRI("http://example.org/gram")));
    triples.add(rdf.createTriple(quantity, hasNumericValue, rdf.createLiteral("100")));

    return triples;
  }

  private String extractFirstBlankNodeId(Collection<Triple> triples) {
    for (Triple triple : triples) {
      if (triple.getSubject() instanceof BlankNode bn) {
        return bn.uniqueReference();
      }
      if (triple.getObject() instanceof BlankNode bn) {
        return bn.uniqueReference();
      }
    }
    return null;
  }

  private String serializeToString(Collection<Triple> triples) {
    StringBuilder sb = new StringBuilder();
    triples.stream()
        .sorted((t1, t2) -> tripleToString(t1).compareTo(tripleToString(t2)))
        .forEach(t -> sb.append(tripleToString(t)).append("\n"));
    return sb.toString();
  }

  private String tripleToString(Triple t) {
    return t.getSubject().toString() + " " + t.getPredicate().toString() + " " + t.getObject().toString();
  }
}
