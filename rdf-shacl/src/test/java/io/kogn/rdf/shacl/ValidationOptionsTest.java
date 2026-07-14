// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidationOptionsTest {

  @Test
  void defaultsDisableRdfsSubClassReasoning() {
    ValidationOptions options = ValidationOptions.defaults();

    assertThat(options.rdfsSubClassReasoning()).isFalse();
  }

  @Test
  void rdfsSubClassReasoningCanBeEnabled() {
    ValidationOptions options = new ValidationOptions(true);

    assertThat(options.rdfsSubClassReasoning()).isTrue();
  }
}
