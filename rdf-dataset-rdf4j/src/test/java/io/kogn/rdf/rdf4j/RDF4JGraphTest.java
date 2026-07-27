// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.IRI;

/**
 * Verifies that a null subject reaching {@code RDF4JGraph#add(BlankNodeOrIRI, IRI, RDFTerm)} fails
 * with a named {@link NullPointerException} rather than the bare one that used to surface out of
 * {@code RDF4JConverters#toRDF4JResource} (issue #85).
 */
class RDF4JGraphTest {

  private static final IRI PREDICATE = RDF4JIRI.of("https://example.org/predicate");
  private static final IRI OBJECT = RDF4JIRI.of("https://example.org/object");

  @Test
  @DisplayName("add with a null subject fails with a clear NullPointerException, not a bare NPE"
      + " out of Object#getClass on the unsupported-type fallback (issue #85)")
  void add_withNullSubject_throwsNullPointerExceptionWithMessage() {
    // given
    final RDF4JGraph graph = new RDF4JGraph();

    // when, then — the failure must name the violated precondition rather than surface as an
    // unqualified NullPointerException out of resource.getClass() on RDF4JConverters'
    // unsupported-type fallback.
    assertThatThrownBy(() -> graph.add(null, PREDICATE, OBJECT)).isInstanceOf(NullPointerException.class)
        .hasMessage("resource must not be null");
  }
}
