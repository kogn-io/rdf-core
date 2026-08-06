// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Structural test: {@code rdf-cid} is backend-neutral and must carry no RDF4J (or any
 * other backend) dependency, so that no backend type can leak onto
 * {@link ContentAddressedIriGenerator}.
 */
class CidPortHasNoBackendDependencyTest {

  @Test
  void rdf4jIsNotOnTheClasspath() {
    assertThatThrownBy(() -> Class.forName("org.eclipse.rdf4j.model.IRI")).isInstanceOf(ClassNotFoundException.class);
  }
}
