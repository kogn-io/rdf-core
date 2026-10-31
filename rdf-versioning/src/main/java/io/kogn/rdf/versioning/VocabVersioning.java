// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.SimpleIRI;

/**
 * The vocabulary {@code rdf-versioning} writes and reads in its own graphs.
 *
 * <p>Namespace {@value #NAMESPACE}, conventional prefix {@code versioning:}. The terms
 * belong to this module, which is the only one that writes them; third-party
 * vocabularies it uses beside them — {@code dcterms:created}, {@code dcterms:creator},
 * {@code dcterms:description} for the metadata of a history entry — come from
 * {@link io.kogn.rdf.terms.vocab.VocabDct}. Names are added, never renamed: stored data
 * binds them (ADR-0023).</p>
 *
 * <p>A history entry in the {@link VersioningGraphs#HISTORY history graph} looks like
 * this:</p>
 *
 * <pre>{@code
 * ex:tree  versioning:entry     <urn:uuid:5b1f0c9e-3a52-4c1e-9d0a-7f2e6a1c4b88> ;
 *          versioning:revision  5 .
 * <urn:uuid:5b1f0c9e-3a52-4c1e-9d0a-7f2e6a1c4b88>
 *          versioning:sequence  7 ;
 *          versioning:content   <ni:///sha3-256;...> ;
 *          versioning:procedure "ni-rdf/1" ;
 *          dcterms:created      "2026-10-31T09:00:00Z"^^xsd:dateTime .
 * }</pre>
 *
 * <p>The namespace is not resolvable yet: its w3id.org redirect is registered once the
 * vocabulary document is in the repository.</p>
 */
public interface VocabVersioning {

  /** The namespace of the versioning vocabulary: {@value} */
  String NAMESPACE = "https://w3id.org/kognio/rdf/versioning#";

  /**
   * The {@code versioning:entry} property: links a proxy IRI to one of its history
   * entries.
   *
   * <p>Subject a proxy IRI, object the {@code urn:uuid:} IRI of an entry, in the history
   * graph. One triple per entry.</p>
   */
  IRI ENTRY = new SimpleIRI(NAMESPACE + "entry");

  /**
   * The {@code versioning:revision} property: the revision counter of a proxy IRI.
   *
   * <p>Subject a proxy IRI, object a non-negative integer, in the history graph. It is
   * raised on every draft write, publish and delete made through the library and backs
   * the caller's opaque concurrency token (ADR-0022). A bookkeeping triple beside the
   * entries, not an entry (ADR-0023).</p>
   */
  IRI REVISION = new SimpleIRI(NAMESPACE + "revision");

  /**
   * The {@code versioning:sequence} property: the sequence number of a history entry.
   *
   * <p>Subject an entry, object a positive integer; numbers run from 1 per proxy IRI,
   * without gaps. The entry with the highest number is the latest: if it is a
   * publish it names the current version, if it is a {@link #TOMBSTONE} the proxy IRI is
   * deleted (ADR-0023).</p>
   */
  IRI SEQUENCE = new SimpleIRI(NAMESPACE + "sequence");

  /**
   * The {@code versioning:content} property: the CID of the version a history entry
   * records.
   *
   * <p>Subject an entry, object the {@code ni:} IRI under which the version's content is
   * kept in the {@link VersioningGraphs#CONTENT_STORE content store}.</p>
   */
  IRI CONTENT = new SimpleIRI(NAMESPACE + "content");

  /**
   * The {@code versioning:procedure} property: the tag of the procedure that derived the
   * CID of a history entry.
   *
   * <p>Subject an entry, object a plain string literal such as {@code "ni-rdf/1"}. It
   * keeps the comparison of CIDs sound once a second procedure exists (ADR-0022).</p>
   */
  IRI PROCEDURE = new SimpleIRI(NAMESPACE + "procedure");

  /**
   * The {@code versioning:Tombstone} class: marks a history entry as the deletion of a
   * proxy IRI.
   *
   * <p>Object of {@code rdf:type} on an entry, in the history graph. A tombstone entry
   * has an IRI, a sequence number and {@code dcterms:created} like a publish, and
   * {@link #CONTENT} and {@link #PROCEDURE} naming the last version of the proxy IRI —
   * both or neither: a proxy IRI that was never published has a tombstone with neither.
   * An entry without this type is a publish.</p>
   */
  IRI TOMBSTONE = new SimpleIRI(NAMESPACE + "Tombstone");

  /**
   * The {@code versioning:contentGraph} property: the content graph a versioned dataset
   * is bound to.
   *
   * <p>Subject the {@link VersioningGraphs#CONFIG config graph} IRI, object the IRI of
   * the caller's content graph, in the config graph. Written on first use, checked on
   * every later use (ADR-0023).</p>
   */
  IRI CONTENT_GRAPH = new SimpleIRI(NAMESPACE + "contentGraph");
}
