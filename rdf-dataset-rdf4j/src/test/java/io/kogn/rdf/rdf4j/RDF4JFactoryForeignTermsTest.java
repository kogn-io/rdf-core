// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Pins that {@link RDF#createTriple} accepts terms of a foreign {@link RDF} implementation
 * (ADR-0004), as {@code graph.add(...)} on the same backend does, and rejects {@code null} with the
 * message of the shared converters.
 */
class RDF4JFactoryForeignTermsTest {

  private static final String S = "http://example.org/s";
  private static final String P = "http://example.org/p";
  private static final String O = "http://example.org/o";

  private final RDF rdf = new RDF4JFactory();
  private final RDF foreign = new SimpleRdf();

  @Test
  void createTripleAcceptsAForeignIriSubject() {
    final Triple triple = rdf.createTriple(foreign.createIRI(S), foreign.createIRI(P), foreign.createIRI(O));

    assertThat(triple.getSubject()).isEqualTo(rdf.createIRI(S));
    assertThat(triple.getPredicate()).isEqualTo(rdf.createIRI(P));
    assertThat(triple.getObject()).isEqualTo(rdf.createIRI(O));
  }

  @Test
  void createTripleAcceptsAForeignBlankNodeSubject() {
    final Triple triple = rdf.createTriple(foreign.createBlankNode("b1"), rdf.createIRI(P), foreign.createLiteral("x"));

    assertThat(triple.getSubject().ntriplesString()).isNotEmpty();
    assertThat(triple.getObject()).isEqualTo(rdf.createLiteral("x"));
  }

  @Test
  void createTripleRejectsANullSubjectWithTheConvertersMessage() {
    final IRI p = rdf.createIRI(P);

    assertThatThrownBy(() -> rdf.createTriple(null, p, p)).isInstanceOf(NullPointerException.class)
        .hasMessageContaining("resource must not be null");
  }
}
