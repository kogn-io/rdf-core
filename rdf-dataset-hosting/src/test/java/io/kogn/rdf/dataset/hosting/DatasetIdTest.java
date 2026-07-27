// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatasetIdTest {

  @Test
  void nonBlankValueIsAccepted() {
    DatasetId id = new DatasetId("dataset-1");

    assertThat(id.value()).isEqualTo("dataset-1");
  }

  @Test
  void nullValueIsRejected() {
    assertThatThrownBy(() -> new DatasetId(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void emptyValueIsRejected() {
    assertThatThrownBy(() -> new DatasetId("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blankValueIsRejected() {
    assertThatThrownBy(() -> new DatasetId("  ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void idsWithTheSameValueAreEqual() {
    assertThat(new DatasetId("dataset-1")).isEqualTo(new DatasetId("dataset-1"));
  }
}
