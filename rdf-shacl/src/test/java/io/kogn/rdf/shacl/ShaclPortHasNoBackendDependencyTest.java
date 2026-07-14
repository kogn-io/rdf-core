// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Structural test: {@code rdf-shacl} is the neutral port and must carry no RDF4J (or
 * any other backend) dependency, so that no backend type can leak onto
 * {@link ShaclValidation}.
 */
class ShaclPortHasNoBackendDependencyTest {

  @Test
  void rdf4jIsNotOnTheClasspath() {
    assertThatThrownBy(() -> Class.forName("org.eclipse.rdf4j.model.IRI")).isInstanceOf(ClassNotFoundException.class);
  }
}
