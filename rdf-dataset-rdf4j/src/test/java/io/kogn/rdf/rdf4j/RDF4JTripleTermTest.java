// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.util.Values;
import org.junit.jupiter.api.Test;

/**
 * An RDF 1.2 triple term, which a SPARQL update can store, has no counterpart in the port data
 * model; a triple rejects it when it is created, with a message that names the cause, instead of
 * failing later on first access.
 */
class RDF4JTripleTermTest {

  private static final String EX = "http://example.org/";

  private static Value tripleTerm() {
    return Values.tripleTerm(Values.iri(EX + "a"), Values.iri(EX + "b"), Values.iri(EX + "c"));
  }

  @Test
  void tripleWithATripleTermObjectIsRejectedOnCreation() {
    assertThatThrownBy(() -> new RDF4JTriple(Values.iri(EX + "s"), Values.iri(EX + "p"), tripleTerm()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("triple term");
  }
}
