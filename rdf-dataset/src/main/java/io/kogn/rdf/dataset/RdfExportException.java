// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * Signals that a dataset could not be serialized to the caller's stream.
 *
 * <p>Thrown by {@link DatasetExport} when writing fails — a broken pipe, a full disk, a
 * closed stream, or a serializer refusing what it was handed. The store itself is
 * unaffected: export is a read, so a failure here says nothing about the data, only that
 * the bytes did not reach their destination.</p>
 *
 * <p>This is the neutral, backend-independent form of that failure. Implementations
 * translate their backend's I/O or handler signal into it and keep the original as
 * {@linkplain Throwable#getCause() cause}, so a caller can react to a failed dump without
 * catching a backend type leaking through a port whose purpose is to keep the backend out
 * of the consumer.</p>
 *
 * <p>Whether a retry can succeed depends on the sink, not on the request: unlike
 * {@link MalformedSparqlException}, which is deterministic, the same export to a fresh
 * stream may well work. Note that a failed export may already have written a prefix of the
 * document — the stream is not rolled back, so a caller retrying must discard whatever the
 * failed attempt produced.</p>
 */
public class RdfExportException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an export exception describing a failed serialization.
   *
   * @param message what went wrong while writing
   * @param cause the backend's original I/O or handler signal; may be {@code null}
   */
  public RdfExportException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
