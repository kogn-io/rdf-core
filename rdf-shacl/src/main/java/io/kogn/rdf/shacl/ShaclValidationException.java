// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.shacl;

/**
 * Signals that a SHACL validation run itself failed — as distinct from a validation
 * <em>result</em> reported through {@link ShaclReport}.
 *
 * <p>Thrown by {@link ShaclValidation#validate} when the backend cannot produce a report at
 * all: the shapes graph cannot be parsed as SHACL, a shape uses a construct the backend does
 * not support, an input graph holds a term the backend rejects, or evaluation otherwise fails
 * before a {@link ShaclReport} can be built. A non-conforming data graph is not this — that
 * is a normal, successful run reported through {@link ShaclReport#conforms()} being
 * {@code false}.</p>
 *
 * <p>This is the neutral, backend-independent form of such a failure. Implementations
 * translate their backend's validation-failure signal into it and keep the original as
 * {@linkplain Throwable#getCause() cause}, so a caller can react to a broken shapes or data
 * graph without catching a backend type leaking through a port whose purpose is to keep the
 * backend out of the consumer.</p>
 */
public class ShaclValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a validation exception describing a failed validation run.
   *
   * @param message what went wrong while validating
   * @param cause the backend's original validation-failure signal; may be {@code null}
   */
  public ShaclValidationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
