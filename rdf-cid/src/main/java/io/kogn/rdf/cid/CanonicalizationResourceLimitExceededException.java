// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

/**
 * Signals that a graph satisfying every documented precondition of
 * {@link ContentAddressedIriGenerator#generateIri} still could not be addressed, because
 * canonicalizing it exceeded the URDNA2015 implementation's resource limit for
 * structurally symmetric blank node graphs.
 *
 * <p>URDNA2015's {@code hash-n-degree-quads} step permutes candidate labels for blank nodes
 * it cannot yet tell apart; a graph with enough symmetric blank node structure — for
 * instance a densely interconnected cluster where every edge carries the same predicate —
 * can drive that permutation count past what the canonicalizer in use is willing to
 * attempt. This is a property of the canonicalizer implementation, not of URDNA2015 itself:
 * another conformant implementation may address a graph this one rejects, and the exact
 * boundary is not part of this port's contract.</p>
 *
 * <p>This is distinct from every other {@link ContentAddressingException}: those signal a
 * derivation that went wrong, this signals a graph that — with the canonicalizer this
 * module ships — has no identifier at all. A caller that wants to tell "not addressable
 * with today's canonicalizer" apart from "broken" catches this subtype specifically rather
 * than inspecting the message or the third-party {@linkplain Throwable#getCause() cause}.</p>
 */
public class CanonicalizationResourceLimitExceededException extends ContentAddressingException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception describing an exceeded canonicalization resource limit.
   *
   * @param message what limit was exceeded
   * @param cause the canonicalizer's own signal; may be {@code null}
   */
  public CanonicalizationResourceLimitExceededException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
