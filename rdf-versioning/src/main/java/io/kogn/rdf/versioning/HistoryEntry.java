// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import io.kogn.rdf.terms.IRI;

/**
 * One entry of a proxy IRI's history, a publish or a tombstone, as the history graph records it (ADR-0022, ADR-0023; see
 * {@link VocabVersioning} for the stored form).
 *
 * @param iri the {@code urn:uuid:} IRI of the entry, the subject of any evidence a caller
 *     attaches to this publish or delete
 * @param sequence the sequence number of the entry, from 1 per proxy IRI, without gaps
 * @param kind whether the entry records a publish or a tombstone
 * @param cid the CID of the version this entry names; always present for a publish; for
 *     a tombstone the last version of the proxy IRI, empty if it was never published
 * @param procedure the tag of the procedure that derived {@code cid}, such as
 *     {@code ni-rdf/1}; present exactly if {@code cid} is
 * @param created when the entry was written
 * @param creator who published or deleted, if recorded
 * @param comment the free-text comment on the publish or delete, if recorded
 */
public record HistoryEntry(IRI iri, long sequence, EntryKind kind, Optional<IRI> cid, Optional<String> procedure,
    Instant created, Optional<IRI> creator, Optional<String> comment) {

  /**
   * Creates the entry.
   *
   * @param iri the entry IRI; must not be {@code null}
   * @param sequence the sequence number; must be at least 1
   * @param kind the kind of the entry; must not be {@code null}
   * @param cid the CID; must not be {@code null}, may be empty for a tombstone only
   * @param procedure the procedure tag; must not be {@code null}, present exactly if
   *     {@code cid} is
   * @param created the time of the publish or delete; must not be {@code null}
   * @param creator who published or deleted; must not be {@code null}, may be empty
   * @param comment the comment; must not be {@code null}, may be empty
   * @throws NullPointerException if an argument is {@code null}
   * @throws IllegalArgumentException if {@code sequence} is less than 1, a publish lacks its CID, or
   *     cid and procedure are not both present or both empty
   */
  public HistoryEntry {
    Objects.requireNonNull(iri, "iri");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(cid, "cid");
    Objects.requireNonNull(procedure, "procedure");
    Objects.requireNonNull(created, "created");
    Objects.requireNonNull(creator, "creator");
    Objects.requireNonNull(comment, "comment");
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be at least 1: " + sequence);
    }
    if (cid.isPresent() != procedure.isPresent()) {
      throw new IllegalArgumentException("cid and procedure must be both present or both empty");
    }
    if (kind == EntryKind.PUBLISH && cid.isEmpty()) {
      throw new IllegalArgumentException("a publish entry names its version");
    }
  }
}
