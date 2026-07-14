// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

/**
 * A single SHACL validation result, corresponding to one {@code sh:ValidationResult}.
 *
 * @param focusNode the string representation of the node that failed validation (an
 *     IRI or blank node identifier); must not be {@code null}
 * @param path the string representation of the {@code sh:resultPath} that caused this
 *     result, or {@code null} if the shape that produced it carries no path (e.g. a
 *     node shape)
 * @param severity the severity of this result; must not be {@code null}
 * @param message the human-readable {@code sh:resultMessage}, or {@code null} if the
 *     shape produced none
 */
public record ShaclResult(String focusNode, String path, Severity severity, String message) {

  /**
   * Validates the result.
   *
   * @throws IllegalArgumentException if {@code focusNode} or {@code severity} is
   *     {@code null}
   */
  public ShaclResult {
    if (focusNode == null) {
      throw new IllegalArgumentException("focusNode must not be null");
    }
    if (severity == null) {
      throw new IllegalArgumentException("severity must not be null");
    }
  }
}
