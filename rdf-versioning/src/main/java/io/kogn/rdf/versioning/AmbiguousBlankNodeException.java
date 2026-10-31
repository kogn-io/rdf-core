// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

/**
 * Thrown when a write would make a blank node part of the slices of two proxy IRIs
 * (ADR-0022).
 *
 * <p>A blank node reachable from two proxy IRIs makes both slices ambiguous, so the
 * write is refused and nothing changes. Give the shared node an IRI of its own, or a
 * fragment IRI of one proxy IRI, or copy it per proxy IRI.</p>
 */
public class AmbiguousBlankNodeException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the detail message
   */
  public AmbiguousBlankNodeException(final String message) {
    super(message);
  }
}
