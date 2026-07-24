// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * Signals that a SPARQL query or update string could not be parsed.
 *
 * <p>Thrown by the query and update ports — {@link SparqlQuery}, {@link SparqlUpdate} and
 * {@link DatasetTx} — when the supplied SPARQL is syntactically invalid and the backend
 * rejects it before evaluation.</p>
 *
 * <p>This is the neutral, backend-independent form of a parse failure. Implementations
 * translate their backend's malformed-query signal into it and keep the original as
 * {@linkplain Throwable#getCause() cause}, so a caller can react to invalid SPARQL without
 * catching a backend type leaking through a port whose purpose is to keep the backend out
 * of the consumer.</p>
 *
 * <p>The failure is deterministic, not transient: the same string will fail again. Fix the
 * query rather than retrying it — unlike {@link ConcurrencyConflictException}, re-running
 * the call unchanged cannot succeed.</p>
 */
public class MalformedSparqlException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a malformed-SPARQL exception describing a rejected parse.
   *
   * @param message what was wrong with the SPARQL string
   * @param cause the backend's original malformed-query signal; may be {@code null}
   */
  public MalformedSparqlException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
