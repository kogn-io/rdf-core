// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UnmodifiableGraphTest {

  @Test
  void ofRejectsNullGraphAtTheCaller() {
    assertThatThrownBy(() -> UnmodifiableGraph.of(null)).isInstanceOf(NullPointerException.class)
        .hasMessageContaining("graph");
  }

  @Test
  void ofReturnsAnAlreadyUnmodifiableGraphAsIs() {
    final ReadableGraph view = UnmodifiableGraph.of(new SimpleGraph());

    assertThat(UnmodifiableGraph.of(view)).isSameAs(view);
  }
}
