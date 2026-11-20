// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * Signals that an RDF document could not be read into the port data model.
 *
 * <p>Thrown by {@link GraphImport} when the document is not valid in its format — a syntax error,
 * an IRI that does not resolve to an absolute one, a malformed escape — when it holds content
 * the port data model cannot represent, such as an RDF 1.2 triple term, or when reading the
 * caller's stream fails. No graph is handed out in any of these cases.</p>
 *
 * <p>This is the neutral, backend-independent form of that failure, the counterpart of
 * {@link RdfExportException} on the reading side. Implementations translate their backend's
 * parse or I/O signal into it and keep the original as {@linkplain Throwable#getCause() cause},
 * so a caller can react to a broken document without catching a backend type leaking through a
 * port whose purpose is to keep the backend out of the consumer.</p>
 *
 * <p>The message names what went wrong, including the position in the document where the
 * backend reports one. Whether a retry can succeed depends on what failed: the same document
 * fails the same way again, while a failed stream may well be read on a second attempt; inspect
 * the cause.</p>
 */
public class RDFImportException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an import exception describing a document that could not be read.
   *
   * @param message what went wrong while reading
   * @param cause the backend's original parse or I/O signal; may be {@code null}
   */
  public RDFImportException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
