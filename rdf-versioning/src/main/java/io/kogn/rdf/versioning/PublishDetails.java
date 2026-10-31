// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.Objects;
import java.util.Optional;

import io.kogn.rdf.terms.IRI;

/**
 * The optional metadata a publish records in each history entry it creates
 * ({@code dcterms:creator}, {@code dcterms:description}, ADR-0023).
 *
 * <p>The metadata is the same for every proxy IRI of one publish call. It does not enter
 * the CID and is not recorded for a proxy IRI whose publish turns out to be a no-op.</p>
 *
 * @param creator who publishes, as an IRI; empty if not recorded
 * @param comment a free-text comment on the publish; empty if not recorded
 */
public record PublishDetails(Optional<IRI> creator, Optional<String> comment) {

  /** No creator and no comment. */
  public static final PublishDetails NONE = new PublishDetails(Optional.empty(), Optional.empty());

  /**
   * Creates the details.
   *
   * @param creator who publishes; must not be {@code null}, may be empty
   * @param comment the comment; must not be {@code null}, may be empty
   * @throws NullPointerException if an argument is {@code null}
   */
  public PublishDetails {
    Objects.requireNonNull(creator, "creator");
    Objects.requireNonNull(comment, "comment");
  }

  /**
   * Returns these details with a creator.
   *
   * @param creator the IRI of the publisher; must not be {@code null}
   * @return the new details; never {@code null}
   * @throws NullPointerException if {@code creator} is {@code null}
   */
  public PublishDetails withCreator(final IRI creator) {
    return new PublishDetails(Optional.of(creator), comment);
  }

  /**
   * Returns these details with a comment.
   *
   * @param comment the comment; must not be {@code null}
   * @return the new details; never {@code null}
   * @throws NullPointerException if {@code comment} is {@code null}
   */
  public PublishDetails withComment(final String comment) {
    return new PublishDetails(creator, Optional.of(comment));
  }
}
