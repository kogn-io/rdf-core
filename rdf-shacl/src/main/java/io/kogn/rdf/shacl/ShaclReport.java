// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

import java.util.List;

/**
 * The outcome of validating a data graph against a set of SHACL shapes.
 *
 * <p>Only {@link Severity#VIOLATION} results affect {@link #conforms()}:
 * {@code sh:Warning} and {@code sh:Info} results are carried in {@link #results()} for
 * inspection but never make an otherwise-conforming report fail.</p>
 *
 * @param conforms {@code true} if no {@link ShaclResult} in {@link #results()} has
 *     {@link Severity#VIOLATION} severity
 * @param results the reported results, one per {@code sh:ValidationResult}; never
 *     {@code null}, possibly empty
 */
public record ShaclReport(boolean conforms, List<ShaclResult> results) {

  /**
   * Validates and defensively copies the report.
   *
   * @throws IllegalArgumentException if {@code results} is {@code null}, or if
   *     {@code conforms} is {@code true} while {@code results} contains a
   *     {@link Severity#VIOLATION}
   */
  public ShaclReport {
    if (results == null) {
      throw new IllegalArgumentException("results must not be null");
    }
    results = List.copyOf(results);
    if (conforms && results.stream().anyMatch(result -> result.severity() == Severity.VIOLATION)) {
      throw new IllegalArgumentException("conforms must be false when results contain a VIOLATION");
    }
  }
}
