// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** The digest and the name of ni-rdf/1 §4.7 and §4.8. */
final class NiName {

  /** The canonical prefix of every name: RFC 6920 {@code ni:} without authority, SHA3-256. */
  static final String PREFIX = "ni:///sha3-256;";

  private static final String DIGEST_ALGORITHM = "SHA3-256";

  private NiName() {
  }

  /**
   * Names a hashed input: {@link #PREFIX} followed by the unpadded base64url encoding of its
   * SHA3-256 digest.
   *
   * @param hashedInput the bytes of §4.5
   * @return the name
   */
  static String of(byte[] hashedInput) {
    return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(sha3256(hashedInput));
  }

  private static byte[] sha3256(byte[] input) {
    try {
      return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(input);
    } catch (NoSuchAlgorithmException e) {
      // SHA3-256 is a JDK-guaranteed MessageDigest algorithm since Java 9.
      throw new IllegalStateException(DIGEST_ALGORITHM + " unavailable", e);
    }
  }
}
