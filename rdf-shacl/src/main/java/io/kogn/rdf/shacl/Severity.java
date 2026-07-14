// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

/**
 * SHACL result severity, mirroring {@code sh:Violation}, {@code sh:Warning} and
 * {@code sh:Info}.
 *
 * <p>Only {@link #VIOLATION} makes a {@link ShaclReport} non-conforming;
 * {@link #WARNING} and {@link #INFO} results are informational and do not affect
 * {@link ShaclReport#conforms()}.</p>
 */
public enum Severity {

  /** A constraint violation ({@code sh:Violation}); makes the report non-conforming. */
  VIOLATION,

  /** A non-fatal warning ({@code sh:Warning}); does not affect conformance. */
  WARNING,

  /** An informational result ({@code sh:Info}); does not affect conformance. */
  INFO
}
