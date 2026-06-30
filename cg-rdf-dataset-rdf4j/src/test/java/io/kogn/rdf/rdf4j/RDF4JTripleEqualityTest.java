// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.rdf4j.model.util.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Verifies the cross-implementation Triple equality contract:
 * two triples with identical S/P/O must be equal and share the same hashCode
 * regardless of whether they are backed by SimpleRdf or RDF4J.
 */
class RDF4JTripleEqualityTest {

  private static final String S = "http://example.org/subject";
  private static final String P = "http://example.org/predicate";
  private static final String O_IRI = "http://example.org/object";
  private static final String O_LITERAL = "hello";

  @Test
  @DisplayName("SimpleTriple and RDF4JTriple with IRI object are equal and share hashCode")
  void tripleWithIriObject_equalsCrossImpl() {
    // given
    final SimpleRdf factory = new SimpleRdf();
    final Triple simple = factory.createTriple(factory.createIRI(S), factory.createIRI(P), factory.createIRI(O_IRI));

    final Triple rdf4j = new RDF4JTriple(Values.iri(S), Values.iri(P), Values.iri(O_IRI));

    // when / then
    assertThat(simple.equals(rdf4j)).as("simple.equals(rdf4j)").isTrue();
    assertThat(rdf4j.equals(simple)).as("rdf4j.equals(simple)").isTrue();
    assertThat(simple.hashCode()).as("hashCode must match").isEqualTo(rdf4j.hashCode());
  }

  @Test
  @DisplayName("SimpleTriple and RDF4JTriple with Literal object are equal and share hashCode")
  void tripleWithLiteralObject_equalsCrossImpl() {
    // given
    final SimpleRdf factory = new SimpleRdf();
    final Triple simple = factory.createTriple(factory.createIRI(S), factory.createIRI(P),
        factory.createLiteral(O_LITERAL));

    // RDF4J xsd:string literal (default plain literal)
    final Triple rdf4j = new RDF4JTriple(Values.iri(S), Values.iri(P), Values.literal(O_LITERAL));

    // when / then
    assertThat(simple.equals(rdf4j)).as("simple.equals(rdf4j)").isTrue();
    assertThat(rdf4j.equals(simple)).as("rdf4j.equals(simple)").isTrue();
    assertThat(simple.hashCode()).as("hashCode must match").isEqualTo(rdf4j.hashCode());
  }

  @Test
  @DisplayName("Triples with different objects are not equal")
  void tripleWithDifferentObject_notEqual() {
    // given
    final SimpleRdf factory = new SimpleRdf();
    final Triple simple = factory.createTriple(factory.createIRI(S), factory.createIRI(P), factory.createIRI(O_IRI));

    final Triple rdf4j = new RDF4JTriple(Values.iri(S), Values.iri(P), Values.iri("http://example.org/other-object"));

    // when / then
    assertThat(simple.equals(rdf4j)).as("must not be equal").isFalse();
    assertThat(rdf4j.equals(simple)).as("must not be equal (symmetric)").isFalse();
  }
}
