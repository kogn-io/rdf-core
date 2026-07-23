// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class ShaclReportTest {

  private static final ShaclResult VIOLATION_RESULT = new ShaclResult("https://example.org/alice",
      "https://example.org/name", Severity.VIOLATION, List.of(ShaclMessage.untagged("missing name")));

  private static final ShaclResult WARNING_RESULT = new ShaclResult("https://example.org/alice",
      "https://example.org/email", Severity.WARNING, List.of(ShaclMessage.untagged("missing email")));

  @Test
  void conformingReportWithNoResultsIsValid() {
    ShaclReport report = new ShaclReport(true, List.of());

    assertThat(report.conforms()).isTrue();
    assertThat(report.results()).isEmpty();
  }

  @Test
  void nonConformingReportWithViolationIsValid() {
    ShaclReport report = new ShaclReport(false, List.of(VIOLATION_RESULT));

    assertThat(report.conforms()).isFalse();
    assertThat(report.results()).containsExactly(VIOLATION_RESULT);
  }

  @Test
  void conformingReportWithOnlyWarningsIsValid() {
    ShaclReport report = new ShaclReport(true, List.of(WARNING_RESULT));

    assertThat(report.conforms()).isTrue();
    assertThat(report.results()).containsExactly(WARNING_RESULT);
  }

  @Test
  void conformsTrueWithAViolationResultIsRejected() {
    assertThatThrownBy(() -> new ShaclReport(true, List.of(VIOLATION_RESULT)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullResultsAreRejected() {
    assertThatThrownBy(() -> new ShaclReport(true, null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resultsAreDefensivelyCopied() {
    List<ShaclResult> mutable = new java.util.ArrayList<>(List.of(WARNING_RESULT));

    ShaclReport report = new ShaclReport(true, mutable);
    mutable.add(VIOLATION_RESULT);

    assertThat(report.results()).containsExactly(WARNING_RESULT);
  }
}
