// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@link RDF#createBlankNode(String)} contract: {@link BlankNode#uniqueReference()}
 * of the returned node equals the identifier passed in.
 *
 * <p>Callers outside this module — {@code rdf-cid}'s skolem naming, for one — round-trip
 * identifiers through this method and rely on getting the same string back. Nothing in
 * {@link RDF}'s type signature enforces that; only a test pins it.</p>
 */
class SimpleRdfBlankNodeIdentifierTest {

  @Test
  void uniqueReferenceEqualsTheGivenIdentifier() {
    RDF rdf = new SimpleRdf();

    BlankNode blankNode = rdf.createBlankNode("some-identifier");

    assertThat(blankNode.uniqueReference()).isEqualTo("some-identifier");
  }
}
