// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * Opaque identifier for a dataset within a {@link DatasetLifecycle}.
 *
 * <p>The {@code value} is an <em>opaque</em> key — it identifies a dataset but
 * carries no structural meaning. In particular it is <strong>not</strong> a
 * file-system path: implementations that persist to disk must map the id to a
 * storage location through their own (sanitising) scheme and must never treat
 * the raw value as a path, so that values such as {@code "../etc"} cannot escape
 * the configured storage root.</p>
 *
 * <p>Any non-blank string is a valid id; callers that key datasets by
 * {@code UUID} simply pass {@code uuid.toString()}.</p>
 *
 * @param value the non-blank identifier; must not be {@code null} or blank
 */
public record DatasetId(String value) {

  /**
   * Validates the identifier.
   *
   * @throws IllegalArgumentException if {@code value} is {@code null} or blank
   */
  public DatasetId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("datasetId value must not be null or blank");
    }
  }
}
