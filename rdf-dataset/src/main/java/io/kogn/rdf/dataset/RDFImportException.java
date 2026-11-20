// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * Signals that an RDF document could not be read into the port data model.
 *
 * <p>Thrown by {@link GraphImport} when</p>
 *
 * <ul>
 *   <li>the document is not valid in its format — a syntax error, an IRI that does not resolve to
 *       an absolute one, a malformed escape, a byte sequence that is not in the format's
 *       encoding;</li>
 *   <li>it holds content the port data model cannot represent — an RDF 1.2 triple term, a
 *       directional language string, an {@code rdf:langString} literal without a language
 *       tag;</li>
 *   <li>it holds a literal the store would refuse — an ill-typed one such as
 *       {@code "abc"^^xsd:integer}, or one with an invalid language tag such as
 *       {@code "x"@abcdefghi} — judged by the same rule as
 *       {@link GraphStore#add(io.kogn.rdf.terms.IRI, io.kogn.rdf.terms.ReadableGraph)};</li>
 *   <li>or reading the caller's stream fails.</li>
 * </ul>
 *
 * <p>No graph is handed out in any of these cases.</p>
 *
 * <p>This is the neutral, backend-independent form of that failure, the counterpart of
 * {@link RdfExportException} on the reading side. Implementations translate their backend's
 * parse or I/O signal into it and keep the original as {@linkplain Throwable#getCause() cause}
 * where there is one, so a caller can react to a broken document without catching a backend type
 * leaking through a port whose purpose is to keep the backend out of the consumer.</p>
 *
 * <p>The message names what went wrong. A position in the document is part of it only where the
 * implementation has one; a failing stream, for instance, has none. Whether a retry can succeed
 * depends on what failed: the same document fails the same way again, while a failed stream may
 * well be read on a second attempt; inspect the cause.</p>
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
