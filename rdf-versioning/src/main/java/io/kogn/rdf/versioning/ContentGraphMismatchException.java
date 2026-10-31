// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.List;
import java.util.Objects;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;

/**
 * Thrown when a versioned dataset is used with a content graph other than the one its
 * config graph records (ADR-0023).
 *
 * <p>A versioned dataset has exactly one content graph. Using it with another would
 * scatter drafts over two graphs, so the operation is refused before it touches the
 * dataset. This is a mismatch between the caller's configuration and the stored data,
 * not a failure of the store: it extends {@link IllegalStateException}, and retrying
 * the same call cannot succeed.</p>
 *
 * <p>The terms it carries are not serializable; after Java deserialization
 * {@link #requested()} and {@link #recorded()} return {@code null}, the message
 * survives.</p>
 */
public class ContentGraphMismatchException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /** The content graph the caller asked for. */
  private final transient IRI requested;

  /** The values the config graph records; never empty. */
  private final transient List<RDFTerm> recorded;

  /**
   * Creates the exception.
   *
   * @param requested the content graph the caller asked for; must not be {@code null}
   * @param recorded the values the config graph records for the content graph; must not
   *     be {@code null} or empty
   * @throws NullPointerException if an argument or an element of {@code recorded} is
   *     {@code null}
   * @throws IllegalArgumentException if {@code recorded} is empty
   */
  public ContentGraphMismatchException(final IRI requested, final List<? extends RDFTerm> recorded) {
    super(message(requested, recorded));
    this.requested = requested;
    this.recorded = List.copyOf(recorded);
  }

  /**
   * Returns the content graph the caller asked for.
   *
   * @return the requested content graph; {@code null} only after deserialization
   */
  public IRI requested() {
    return requested;
  }

  /**
   * Returns what the config graph records as the content graph.
   *
   * <p>Normally one IRI. More than one value, or a value that is no IRI, means the config
   * graph was written past the library.</p>
   *
   * @return the recorded values, unmodifiable and never empty; {@code null} only after
   *     deserialization
   */
  public List<RDFTerm> recorded() {
    return recorded;
  }

  private static String message(final IRI requested, final List<? extends RDFTerm> recorded) {
    Objects.requireNonNull(requested, "requested");
    Objects.requireNonNull(recorded, "recorded");
    if (recorded.isEmpty()) {
      throw new IllegalArgumentException("recorded must not be empty");
    }
    final StringBuilder recordedText = new StringBuilder();
    for (final RDFTerm term : recorded) {
      Objects.requireNonNull(term, "recorded element");
      if (!recordedText.isEmpty()) {
        recordedText.append(", ");
      }
      recordedText.append(term.ntriplesString());
    }
    return "versioned dataset is bound to content graph " + recordedText + ", not " + requested.ntriplesString();
  }
}
