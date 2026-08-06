// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

/**
 * Signals that deriving a content-addressed IRI failed — as distinct from the input
 * being unsuitable, which is reported as {@link IllegalArgumentException}.
 *
 * <p>Thrown by {@link ContentAddressedIriGenerator#generateIri} when the graph satisfies
 * the documented preconditions but the derivation itself cannot complete: canonicalization
 * fails, the digest cannot be computed, or a term cannot be brought into canonical form.
 * A graph that is empty, holds no or several IRI subjects, or holds triples unreachable
 * from an IRI subject is not this — those are input errors and surface as
 * {@link IllegalArgumentException}.</p>
 *
 * <p>The underlying failure is kept as {@linkplain Throwable#getCause() cause}, so a caller
 * can inspect it without the third-party canonicalization library leaking through a port
 * whose whole point is to keep it out of the consumer.</p>
 */
public class ContentAddressingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception describing a failed derivation.
   *
   * @param message what went wrong while deriving the identifier
   * @param cause the original failure signal; may be {@code null}
   */
  public ContentAddressingException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
