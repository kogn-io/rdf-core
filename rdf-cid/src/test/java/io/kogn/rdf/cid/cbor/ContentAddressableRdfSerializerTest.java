// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.cbor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Unit tests for {@link ContentAddressableRdfSerializer}.
 *
 * <p>Tests deterministic CID generation, especially for graphs with BlankNodes.</p>
 */
class ContentAddressableRdfSerializerTest {

  private ContentAddressableRdfSerializer serializer;
  private RDF rdf;

  @BeforeEach
  void setUp() {
    RdfDatasetCanonicalizer canonicalizer = new RdfDatasetCanonicalizer();
    serializer = new ContentAddressableRdfSerializer(canonicalizer);
    rdf = new SimpleRdf();
  }

  @Test
  @DisplayName("identical graphs without BlankNodes should produce identical CIDs")
  void identicalGraphsWithoutBlankNodesShouldProduceIdenticalCids() {
    // Given: Two identical graphs without BlankNodes
    List<Triple> graph1 = createSimpleGraph();
    List<Triple> graph2 = createSimpleGraph();

    // When: Generate CIDs
    IRI cid1 = serializer.serializeWithUrn(graph1).iris().findFirst().orElseThrow();
    IRI cid2 = serializer.serializeWithUrn(graph2).iris().findFirst().orElseThrow();

    // Then: CIDs should be identical
    assertThat(cid1.getIRIString()).isEqualTo(cid2.getIRIString());
  }

  @Test
  @DisplayName("identical graphs with BlankNodes should produce identical CIDs")
  void identicalGraphsWithBlankNodesShouldProduceIdenticalCids() {
    // Given: Two identical graphs with DIFFERENT BlankNode IDs (simulating two imports)
    List<Triple> graph1 = createNestedResourceGraph("table1", "entry1", "quantity1");
    List<Triple> graph2 = createNestedResourceGraph("xyz", "abc", "def");

    // When: Generate CIDs
    IRI cid1 = serializer.serializeWithUrn(graph1).iris().findFirst().orElseThrow();
    IRI cid2 = serializer.serializeWithUrn(graph2).iris().findFirst().orElseThrow();

    // Then: CIDs should be identical (same data, just different BlankNode IDs)
    assertThat(cid1.getIRIString()).as("Identical content with different BlankNode IDs should produce same CID")
        .isEqualTo(cid2.getIRIString());
  }

  @Test
  @DisplayName("simulated duplicate import should produce identical CIDs")
  void simulatedDuplicateImportShouldProduceIdenticalCids() {
    // First import
    List<Triple> firstImport = createResourceGraph("R100000", "Example Resource", "nt1", "ne1", "qv1", 100.0);

    // Second import (same data, but new BlankNode IDs)
    List<Triple> secondImport = createResourceGraph("R100000", "Example Resource", "completely_different",
        "also_different", "random_id", 100.0);

    // Generate CIDs
    IRI cid1 = serializer.serializeWithUrn(firstImport).iris().findFirst().orElseThrow();
    IRI cid2 = serializer.serializeWithUrn(secondImport).iris().findFirst().orElseThrow();

    // Then: CIDs should be identical
    assertThat(cid1.getIRIString()).as("Duplicate import with same data should produce same CID")
        .isEqualTo(cid2.getIRIString());
  }

  @Test
  @DisplayName("different data should produce different CIDs")
  void differentDataShouldProduceDifferentCids() {
    // Given: Two graphs with different measurement values
    List<Triple> graph1 = createResourceGraph("R100000", "Example Resource", "nt1", "ne1", "qv1", 100.0);
    List<Triple> graph2 = createResourceGraph("R100000", "Example Resource", "nt2", "ne2", "qv2", 200.0); // Different
    // value!

    // When: Generate CIDs
    IRI cid1 = serializer.serializeWithUrn(graph1).iris().findFirst().orElseThrow();
    IRI cid2 = serializer.serializeWithUrn(graph2).iris().findFirst().orElseThrow();

    // Then: CIDs should be different
    assertThat(cid1.getIRIString()).as("Different measurement values should produce different CIDs")
        .isNotEqualTo(cid2.getIRIString());
  }

  // Helper methods

  private List<Triple> createSimpleGraph() {
    List<Triple> triples = new ArrayList<>();
    IRI resource = rdf.createIRI("http://example.org/resource/123");
    IRI hasName = rdf.createIRI("http://schema.org/name");
    triples.add(rdf.createTriple(resource, hasName, rdf.createLiteral("Test Resource")));
    return triples;
  }

  private List<Triple> createNestedResourceGraph(String tableId, String entryId, String quantityId) {
    List<Triple> triples = new ArrayList<>();

    IRI resource = rdf.createIRI("http://example.org/resource/123");
    IRI rdfType = rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
    IRI resourceType = rdf.createIRI("http://example.org/vocab#Resource");
    IRI hasName = rdf.createIRI("http://schema.org/name");
    IRI hasMeasurementTable = rdf.createIRI("http://example.org/vocab#hasMeasurementTable");
    IRI measurementTableType = rdf.createIRI("http://example.org/vocab#MeasurementTable");
    IRI hasEntry = rdf.createIRI("http://example.org/vocab#hasMeasurementEntry");
    IRI entryType = rdf.createIRI("http://example.org/vocab#MeasurementEntry");
    IRI hasQuantity = rdf.createIRI("http://example.org/vocab#hasMeasurementQuantity");
    IRI quantityType = rdf.createIRI("http://qudt.org/schema/qudt/QuantityValue");
    IRI numericValue = rdf.createIRI("http://qudt.org/schema/qudt/numericValue");

    BlankNode table = rdf.createBlankNode(tableId);
    BlankNode entry = rdf.createBlankNode(entryId);
    BlankNode quantity = rdf.createBlankNode(quantityId);

    // Resource triples (IRI subject)
    triples.add(rdf.createTriple(resource, rdfType, resourceType));
    triples.add(rdf.createTriple(resource, hasName, rdf.createLiteral("Test Resource")));
    triples.add(rdf.createTriple(resource, hasMeasurementTable, table)); // Object is BlankNode!

    // MeasurementTable triples (BlankNode subject)
    triples.add(rdf.createTriple(table, rdfType, measurementTableType));
    triples.add(rdf.createTriple(table, hasEntry, entry));

    // MeasurementEntry triples (BlankNode subject)
    triples.add(rdf.createTriple(entry, rdfType, entryType));
    triples.add(rdf.createTriple(entry, hasQuantity, quantity));

    // QuantityValue triples (BlankNode subject)
    triples.add(rdf.createTriple(quantity, rdfType, quantityType));
    triples.add(rdf.createTriple(quantity, numericValue, rdf.createLiteral("100")));

    return triples;
  }

  private List<Triple> createResourceGraph(String resourceKey, String name, String tableId, String entryId,
      String quantityId, double measurementValue) {
    List<Triple> triples = new ArrayList<>();

    IRI resource = rdf.createIRI("http://example.org/resource/" + resourceKey);
    IRI rdfType = rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
    IRI resourceType = rdf.createIRI("http://example.org/vocab#Resource");
    IRI hasName = rdf.createIRI("http://schema.org/name");
    IRI code = rdf.createIRI("http://example.org/vocab#code");
    IRI hasMeasurementTable = rdf.createIRI("http://example.org/vocab#hasMeasurementTable");
    IRI measurementTableType = rdf.createIRI("http://example.org/vocab#MeasurementTable");
    IRI hasEntry = rdf.createIRI("http://example.org/vocab#hasMeasurementEntry");
    IRI entryType = rdf.createIRI("http://example.org/vocab#MeasurementEntry");
    IRI hasMeasurementType = rdf.createIRI("http://example.org/vocab#hasMeasurementType");
    IRI measurementType = rdf.createIRI("http://example.org/vocab#Measurement");
    IRI hasQuantity = rdf.createIRI("http://example.org/vocab#hasMeasurementQuantity");
    IRI quantityType = rdf.createIRI("http://qudt.org/schema/qudt/QuantityValue");
    IRI numericValue = rdf.createIRI("http://qudt.org/schema/qudt/numericValue");
    IRI unit = rdf.createIRI("http://qudt.org/schema/qudt/unit");
    IRI kilogram = rdf.createIRI("http://qudt.org/vocab/unit/KiloGM");

    BlankNode table = rdf.createBlankNode(tableId);
    BlankNode entry = rdf.createBlankNode(entryId);
    BlankNode quantity = rdf.createBlankNode(quantityId);

    // Resource triples
    triples.add(rdf.createTriple(resource, rdfType, resourceType));
    triples.add(rdf.createTriple(resource, hasName, rdf.createLiteral(name)));
    triples.add(rdf.createTriple(resource, code, rdf.createLiteral(resourceKey)));
    triples.add(rdf.createTriple(resource, hasMeasurementTable, table));

    // MeasurementTable
    triples.add(rdf.createTriple(table, rdfType, measurementTableType));
    triples.add(rdf.createTriple(table, hasEntry, entry));

    // MeasurementEntry
    triples.add(rdf.createTriple(entry, rdfType, entryType));
    triples.add(rdf.createTriple(entry, hasMeasurementType, measurementType));
    triples.add(rdf.createTriple(entry, hasQuantity, quantity));

    // QuantityValue
    triples.add(rdf.createTriple(quantity, rdfType, quantityType));
    triples.add(rdf.createTriple(quantity, numericValue, rdf.createLiteral(String.valueOf(measurementValue))));
    triples.add(rdf.createTriple(quantity, unit, kilogram));

    return triples;
  }
}
