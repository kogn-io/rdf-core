// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.cbor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
  void testCanonicalizationIsDeterministic() {
    // Create two identical graphs with different BlankNode IDs
    List<Triple> graph1 = createNutrientGraph("bn1", "bn2");
    List<Triple> graph2 = createNutrientGraph("xyz", "abc");

    // The BlankNode IDs should differ
    String bn1_graph1 = extractFirstBlankNodeId(graph1);
    String bn1_graph2 = extractFirstBlankNodeId(graph2);
    assertNotEquals(bn1_graph1, bn1_graph2, "BlankNode IDs should differ");

    // Canonicalize both graphs
    Collection<Triple> canonical1 = canonicalizer.canonicalize(graph1);
    Collection<Triple> canonical2 = canonicalizer.canonicalize(graph2);

    // After canonicalization the BlankNode IDs should be identical
    String canonicalBn1 = extractFirstBlankNodeId(canonical1);
    String canonicalBn2 = extractFirstBlankNodeId(canonical2);
    assertEquals(canonicalBn1, canonicalBn2, "Canonicalized BlankNode IDs should be identical for identical graphs");

    // The serialized forms should be byte-identical
    String serialized1 = serializeToString(canonical1);
    String serialized2 = serializeToString(canonical2);
    assertEquals(serialized1, serialized2, "Canonicalized graphs should serialize identically");
  }

  @Test
  void testNestedBlankNodes() {
    // Test with nested BlankNodes (e.g. table -> entry -> quantity)
    List<Triple> graph1 = createNestedBlankNodeGraph("table1", "entry1", "value1");
    List<Triple> graph2 = createNestedBlankNodeGraph("xyz", "abc", "def");

    Collection<Triple> canonical1 = canonicalizer.canonicalize(graph1);
    Collection<Triple> canonical2 = canonicalizer.canonicalize(graph2);

    String serialized1 = serializeToString(canonical1);
    String serialized2 = serializeToString(canonical2);
    assertEquals(serialized1, serialized2, "Nested BlankNodes should canonicalize deterministically");
  }

  @Test
  void testLiteralsWithDatatypes() {
    // Verify that datatype information is preserved
    IRI subject = rdf.createIRI("http://example.org/resource");
    IRI predicate = rdf.createIRI("http://example.org/value");

    List<Triple> graph = new ArrayList<>();
    graph.add(rdf.createTriple(subject, predicate,
        rdf.createLiteral("123.45", rdf.createIRI(VocabXsd.DECIMAL.getIRIString()))));

    Collection<Triple> canonical = canonicalizer.canonicalize(graph);

    // Verify that the Literal with its datatype is preserved
    assertEquals(1, canonical.size(), "Graph should contain one triple");
    Triple triple = canonical.iterator().next();

    io.kogn.rdf.terms.RDFTerm object = triple.getObject();
    if (object instanceof Literal lit) {
      assertEquals("123.45", lit.getLexicalForm(), "Lexical form should be preserved");
      IRI xsdDecimal = rdf.createIRI(VocabXsd.DECIMAL.getIRIString());
      assertEquals(xsdDecimal, lit.getDatatype(), "Datatype should be preserved");
    } else {
      throw new AssertionError("Object should be a Literal");
    }
  }

  @Test
  void testLiteralsWithLanguageTags() {
    // The round trip through Titanium has a separate branch for language-tagged literals.
    // If it dropped the tag, "Bank"@en and "Bank"@de would reach the serializer as the same
    // literal and share an identifier — a collision the serializer could no longer prevent.
    IRI subject = rdf.createIRI("http://example.org/resource");
    IRI predicate = rdf.createIRI("http://example.org/label");

    List<Triple> graph = new ArrayList<>();
    graph.add(rdf.createTriple(subject, predicate, rdf.createLiteral("Bank", "en")));

    Collection<Triple> canonical = canonicalizer.canonicalize(graph);

    assertEquals(1, canonical.size(), "Graph should contain one triple");
    io.kogn.rdf.terms.RDFTerm object = canonical.iterator().next().getObject();
    if (object instanceof Literal lit) {
      assertEquals("Bank", lit.getLexicalForm(), "Lexical form should be preserved");
      assertEquals(Optional.of("en"), lit.getLanguageTag(), "Language tag should be preserved");
    } else {
      throw new AssertionError("Object should be a Literal");
    }
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
