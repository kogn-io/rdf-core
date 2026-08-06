// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.RDF;

/**
 * Pins the {@link RDF#createBlankNode(String)} contract for the RDF4J backend:
 * {@link BlankNode#uniqueReference()} of the returned node equals the identifier passed in.
 *
 * <p>Callers outside this module — {@code rdf-cid}'s skolem mapping, for one — round-trip
 * identifiers through this method and rely on getting the same string back regardless of
 * which {@link RDF} implementation is wired in.</p>
 */
class RDF4JFactoryBlankNodeIdentifierTest {

  @Test
  void uniqueReferenceEqualsTheGivenIdentifier() {
    RDF rdf = new RDF4JFactory();

    BlankNode blankNode = rdf.createBlankNode("some-identifier");

    assertThat(blankNode.uniqueReference()).isEqualTo("some-identifier");
  }
}
