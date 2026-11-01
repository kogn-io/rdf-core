// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.Objects;

import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.terms.IRI;

/**
 * Thrown when a write presents a {@link RevisionToken} that is no longer current for its
 * proxy IRI: another write through the library came in between read and write
 * (ADR-0022).
 *
 * <p>This is a lost race of the same kind as the one the store reports on commit, so it
 * is a {@link ConcurrencyConflictException}: one {@code catch} covers both, and a caller
 * that wants the stale-token case apart catches this subtype first. It is deliberately
 * not an {@link IllegalStateException}, which {@code DatasetTx} reserves for programming
 * errors that must not be retried.</p>
 *
 * <p>The write did not happen. The library does not retry; re-reading the slice and
 * deciding whether to write again is the caller's decision.</p>
 */
public class RevisionConflictException extends ConcurrencyConflictException {

  private static final long serialVersionUID = 1L;

  /** The proxy IRI the write addressed. */
  private final transient IRI proxyIri;

  /**
   * Creates the exception.
   *
   * @param proxyIri the proxy IRI the write addressed; must not be {@code null}
   * @throws NullPointerException if {@code proxyIri} is {@code null}
   */
  public RevisionConflictException(final IRI proxyIri) {
    super("stale revision token for " + Objects.requireNonNull(proxyIri, "proxyIri").ntriplesString(), null);
    this.proxyIri = proxyIri;
  }

  /**
   * Returns the proxy IRI whose token was stale.
   *
   * @return the proxy IRI; {@code null} only after deserialization
   */
  public IRI proxyIri() {
    return proxyIri;
  }
}
