// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * Signals that a well-formed SPARQL query or update failed while it was being evaluated.
 *
 * <p>Thrown by the query and update ports — {@link SparqlQuery}, {@link SparqlUpdate} and
 * {@link DatasetTx} — when the SPARQL string parsed, but the backend could not carry it out: a
 * federated {@code SERVICE} clause whose endpoint is unreachable, a {@code LOAD} whose source
 * cannot be fetched, a {@code CREATE GRAPH} for a graph that already exists, or a storage
 * failure while the result was being read.</p>
 *
 * <p>This is the neutral, backend-independent form of an evaluation failure, the counterpart
 * of {@link MalformedSparqlException} for the stage after parsing. Implementations translate
 * their backend's evaluation signal into it and keep the original as
 * {@linkplain Throwable#getCause() cause}, so a caller can handle every failure of a SPARQL
 * call without catching a backend type leaking through a port whose purpose is to keep the
 * backend out of the consumer.</p>
 *
 * <p>Whether a retry can succeed depends on what failed, which the type alone does not say:
 * an unreachable endpoint may come back, a {@code CREATE GRAPH} for an existing graph will
 * fail again. Inspect the message, or the cause, before retrying. An update that fails this
 * way outside a transaction may have applied part of its operations if the backend does not
 * execute updates atomically (see {@link SparqlUpdate#update(String)}); inside
 * {@link DatasetTransactor#inTransaction} the exception rolls the whole unit of work back like
 * any other.</p>
 */
public class SparqlEvaluationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an evaluation exception describing a failed query or update run.
   *
   * @param message what went wrong while evaluating
   * @param cause the backend's original evaluation signal; may be {@code null}
   */
  public SparqlEvaluationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
