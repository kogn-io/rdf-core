// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ShaclResultTest {

  private static final ShaclMessage GERMAN = new ShaclMessage("Name fehlt", "de");
  private static final ShaclMessage ENGLISH = new ShaclMessage("Name is required", "en");

  @Test
  void pathMayBeNullAndMessagesMayBeEmpty() {
    ShaclResult result = new ShaclResult("https://example.org/alice", null, Severity.VIOLATION, List.of());

    assertThat(result.focusNode()).isEqualTo("https://example.org/alice");
    assertThat(result.path()).isNull();
    assertThat(result.messages()).isEmpty();
    assertThat(result.severity()).isEqualTo(Severity.VIOLATION);
  }

  @Test
  void allMessagesAreKept() {
    ShaclResult result = new ShaclResult("https://example.org/alice", "https://example.org/name", Severity.VIOLATION,
        List.of(GERMAN, ENGLISH));

    assertThat(result.messages()).containsExactly(GERMAN, ENGLISH);
  }

  @Test
  void nullFocusNodeIsRejected() {
    assertThatThrownBy(() -> new ShaclResult(null, null, Severity.VIOLATION, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullSeverityIsRejected() {
    assertThatThrownBy(() -> new ShaclResult("https://example.org/alice", null, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullMessagesAreRejected() {
    assertThatThrownBy(() -> new ShaclResult("https://example.org/alice", null, Severity.VIOLATION, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void messagesAreDefensivelyCopied() {
    List<ShaclMessage> mutable = new ArrayList<>(List.of(GERMAN));

    ShaclResult result = new ShaclResult("https://example.org/alice", null, Severity.VIOLATION, mutable);
    mutable.add(ENGLISH);

    assertThat(result.messages()).containsExactly(GERMAN);
  }
}
