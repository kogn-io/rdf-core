// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

class RdfFormatTest {

  @ParameterizedTest
  @EnumSource(value = RdfFormat.class, names = {"TRIG", "NQUADS"})
  void quadCapableFormatsCarryGraphNames(final RdfFormat format) {
    assertThat(format.isQuadCapable()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(value = RdfFormat.class, names = {"TRIG", "NQUADS"}, mode = Mode.EXCLUDE)
  void everyOtherFormatIsTripleOnly(final RdfFormat format) {
    assertThat(format.isQuadCapable()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(value = RdfFormat.class, names = {"TRIG", "NQUADS"})
  void requireQuadCapablePassesForAQuadFormat(final RdfFormat format) {
    assertThatCode(format::requireQuadCapable).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @EnumSource(value = RdfFormat.class, names = {"TRIG", "NQUADS"}, mode = Mode.EXCLUDE)
  void requireQuadCapableRejectsATripleOnlyFormat(final RdfFormat format) {
    assertThatThrownBy(format::requireQuadCapable).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(format.name());
  }

  @Test
  void requireQuadCapableNamesTheUsableFormats() {
    assertThatThrownBy(RdfFormat.TURTLE::requireQuadCapable).hasMessageContaining("TRIG")
        .hasMessageContaining("NQUADS");
  }
}
