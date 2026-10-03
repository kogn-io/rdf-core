// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

/**
 * Signals that a graph satisfying every precondition of
 * {@link ContentAddressedIriGenerator#generateIri} still gets no identifier, because its blank
 * node structure exceeds the resource limit of ni-rdf/1 (§4.3 of
 * {@code docs/spec/ni-rdf/v1.md}).
 *
 * <p>RDFC-1.0 runs in factorial time on blank nodes it cannot tell apart by their own triples
 * and that have several mutually indistinguishable neighbours: it tries every order of those
 * neighbours, recursively. The specification counts exactly those blank nodes — the
 * <em>core</em> — before canonicalizing, and rejects a graph whose core holds more than 6 of
 * them, for instance 7 blank nodes all linked to each other by one predicate. The criterion
 * depends on the graph alone, so every conforming implementation rejects the same graphs,
 * whatever its speed; the message starts with {@code [RESOURCE_LIMIT]}.</p>
 *
 * <p>This is distinct from every other {@link ContentAddressingException}: those signal a
 * derivation that went wrong, this signals a graph that has no identifier under ni-rdf/1 at
 * all. A caller that wants to tell "not addressable" apart from "broken" catches this subtype
 * specifically rather than inspecting the message.</p>
 */
public class CanonicalizationResourceLimitExceededException extends ContentAddressingException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception describing an exceeded canonicalization resource limit.
   *
   * @param message what limit was exceeded
   * @param cause the underlying signal, if any; may be {@code null}
   */
  public CanonicalizationResourceLimitExceededException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
