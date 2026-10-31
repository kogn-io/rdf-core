// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import io.kogn.rdf.terms.IRI;

/**
 * One publish of a proxy IRI as the history graph records it (ADR-0022, ADR-0023; see
 * {@link VocabVersioning} for the stored form).
 *
 * @param iri the {@code urn:uuid:} IRI of the entry, the subject of any evidence a caller
 *     attaches to this publish
 * @param sequence the sequence number of the entry, from 1 per proxy IRI, without gaps
 * @param content the CID of the version this entry names
 * @param procedure the tag of the procedure that derived {@code content}, such as
 *     {@code ni-rdf/1}
 * @param created when the entry was written
 * @param creator who published, if recorded
 * @param comment the free-text comment on the publish, if recorded
 */
public record HistoryEntry(IRI iri, long sequence, IRI content, String procedure, Instant created,
    Optional<IRI> creator, Optional<String> comment) {

  /**
   * Creates the entry.
   *
   * @param iri the entry IRI; must not be {@code null}
   * @param sequence the sequence number; must be at least 1
   * @param content the CID; must not be {@code null}
   * @param procedure the procedure tag; must not be {@code null}
   * @param created the time of the publish; must not be {@code null}
   * @param creator who published; must not be {@code null}, may be empty
   * @param comment the comment; must not be {@code null}, may be empty
   * @throws NullPointerException if an argument is {@code null}
   * @throws IllegalArgumentException if {@code sequence} is less than 1
   */
  public HistoryEntry {
    Objects.requireNonNull(iri, "iri");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(procedure, "procedure");
    Objects.requireNonNull(created, "created");
    Objects.requireNonNull(creator, "creator");
    Objects.requireNonNull(comment, "comment");
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be at least 1: " + sequence);
    }
  }
}
