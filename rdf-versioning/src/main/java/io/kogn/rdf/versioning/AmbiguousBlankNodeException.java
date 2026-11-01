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
 *
 * <p>The usual cause is a label shared by accident: blank-node identity is the label,
 * and a parser that numbers labels per document ({@code _:b0}, {@code _:b1}, ...) reuses
 * them across documents, so slices parsed separately collide. Use labels that are unique
 * across the content graph, or write back the nodes {@code readSlice} returned.</p>
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
