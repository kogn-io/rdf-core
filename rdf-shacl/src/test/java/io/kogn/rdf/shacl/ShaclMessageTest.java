// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ShaclMessageTest {

  @Test
  void taggedMessageKeepsItsTagAsWritten() {
    ShaclMessage message = new ShaclMessage("Name fehlt", "de-AT");

    assertThat(message.text()).isEqualTo("Name fehlt");
    assertThat(message.language()).isEqualTo("de-AT");
    assertThat(message.isUntagged()).isFalse();
  }

  @Test
  void untaggedMessageHasNullLanguage() {
    ShaclMessage message = ShaclMessage.untagged("Name is required");

    assertThat(message.language()).isNull();
    assertThat(message.isUntagged()).isTrue();
  }

  @Test
  void nullTextIsRejected() {
    assertThatThrownBy(() -> new ShaclMessage(null, "de")).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Pins the reason {@code null} rather than {@code ""} models "untagged": an empty
   * string looks like a legal tag to a case-insensitive comparison, so letting it
   * through would make "no language" silently pass for "some language".
   */
  @Test
  void blankLanguageIsRejectedRatherThanTreatedAsUntagged() {
    assertThatThrownBy(() -> new ShaclMessage("Name is required", "")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ShaclMessage("Name is required", "  ")).isInstanceOf(IllegalArgumentException.class);
  }
}
