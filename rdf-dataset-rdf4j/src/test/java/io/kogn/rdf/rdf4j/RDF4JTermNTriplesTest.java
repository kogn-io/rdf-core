// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;

/** Pins that {@link RDF4JTerm#ntriplesString()} keeps the {@code "N-Triples:"} contract for an empty blank node ID. */
class RDF4JTermNTriplesTest {

  @Test
  void blankNodeWithAnEmptyIdIsRejectedWithTheNTriplesPrefix() {
    final RDF4JBlankNode term = new RDF4JBlankNode(SimpleValueFactory.getInstance().createBNode(""));

    assertThatThrownBy(term::ntriplesString).isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("N-Triples:");
  }
}
