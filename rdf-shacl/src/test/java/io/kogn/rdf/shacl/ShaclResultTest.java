// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ShaclResultTest {

  @Test
  void pathAndMessageMayBeNull() {
    ShaclResult result = new ShaclResult("https://example.org/alice", null, Severity.VIOLATION, null);

    assertThat(result.focusNode()).isEqualTo("https://example.org/alice");
    assertThat(result.path()).isNull();
    assertThat(result.message()).isNull();
    assertThat(result.severity()).isEqualTo(Severity.VIOLATION);
  }

  @Test
  void nullFocusNodeIsRejected() {
    assertThatThrownBy(() -> new ShaclResult(null, null, Severity.VIOLATION, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullSeverityIsRejected() {
    assertThatThrownBy(() -> new ShaclResult("https://example.org/alice", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
