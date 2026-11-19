// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import io.kogn.rdf.terms.IRI;

/**
 * The outcome of one derivation of ni-rdf/1: the identifier together with the hashed input it
 * is the digest of (§1.2).
 *
 * @param identifier the {@code ni:///sha3-256;…} name
 * @param hashedInput the bytes of §4.5 that {@code identifier} was derived from; copied on
 *     construction and on every {@link #hashedInput()} call, so neither the caller's array nor
 *     a returned copy can change what this result reports having hashed
 */
record Derivation(IRI identifier, byte[] hashedInput) {

  /** Copies {@code hashedInput} so this result owns an immutable snapshot of it. */
  Derivation {
    hashedInput = hashedInput.clone();
  }

  /**
   * Returns the hashed input {@link #identifier()} was derived from.
   *
   * @return a copy of the bytes
   */
  @Override
  public byte[] hashedInput() {
    return hashedInput.clone();
  }
}
