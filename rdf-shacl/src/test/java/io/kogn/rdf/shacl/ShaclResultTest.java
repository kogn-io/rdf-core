// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;

class ShaclResultTest {

  private static final RDF RDF_FACTORY = new SimpleRdf();
  private static final IRI ALICE = RDF_FACTORY.createIRI("https://example.org/alice");
  private static final IRI VIOLATION_IRI = RDF_FACTORY.createIRI("http://www.w3.org/ns/shacl#Violation");

  private static final ShaclMessage GERMAN = new ShaclMessage("Name fehlt", "de");
  private static final ShaclMessage ENGLISH = new ShaclMessage("Name is required", "en");

  @Test
  void pathMayBeNullAndMessagesMayBeEmpty() {
    ShaclResult result = new ShaclResult(ALICE, null, Severity.VIOLATION, VIOLATION_IRI, List.of());

    assertThat(result.focusNode()).isEqualTo(ALICE);
    assertThat(result.path()).isNull();
    assertThat(result.messages()).isEmpty();
    assertThat(result.severity()).isEqualTo(Severity.VIOLATION);
  }

  @Test
  void allMessagesAreKept() {
    ShaclResult result = new ShaclResult(ALICE, "<https://example.org/name>", Severity.VIOLATION, VIOLATION_IRI,
        List.of(GERMAN, ENGLISH));

    assertThat(result.messages()).containsExactly(GERMAN, ENGLISH);
  }

  @Test
  void nullFocusNodeIsRejected() {
    assertThatThrownBy(() -> new ShaclResult(null, null, Severity.VIOLATION, VIOLATION_IRI, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullSeverityIsRejected() {
    assertThatThrownBy(() -> new ShaclResult(ALICE, null, null, VIOLATION_IRI, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullMessagesAreRejected() {
    assertThatThrownBy(() -> new ShaclResult(ALICE, null, Severity.VIOLATION, VIOLATION_IRI, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void messagesAreDefensivelyCopied() {
    List<ShaclMessage> mutable = new ArrayList<>(List.of(GERMAN));

    ShaclResult result = new ShaclResult(ALICE, null, Severity.VIOLATION, VIOLATION_IRI, mutable);
    mutable.add(ENGLISH);

    assertThat(result.messages()).containsExactly(GERMAN);
  }

  @Test
  void nullSeverityIriIsRejected() {
    assertThatThrownBy(() -> new ShaclResult(ALICE, null, Severity.VIOLATION, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void focusNodeKeepsItsTermKindAndSeverityIriIsKept() {
    RDFTerm literal = RDF_FACTORY.createLiteral("42",
        RDF_FACTORY.createIRI("http://www.w3.org/2001/XMLSchema#integer"));
    IRI custom = RDF_FACTORY.createIRI("https://example.org/Critical");

    ShaclResult result = new ShaclResult(literal, null, Severity.VIOLATION, custom, List.of());

    assertThat(result.focusNode()).isSameAs(literal);
    assertThat(result.severityIri()).isEqualTo(custom);
  }
}
