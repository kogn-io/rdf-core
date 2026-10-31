// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.List;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.SimpleIRI;

/**
 * The fixed IRIs of the named graphs {@code rdf-versioning} owns in a versioned dataset.
 *
 * <p>A versioned dataset keeps five graphs in one dataset (ADR-0023): the draft view,
 * which is the caller's content graph and named by the caller, and the four graphs
 * here, whose IRIs are the library's and the same in every dataset. Consumers name them
 * in SPARQL ({@code FROM} / {@code GRAPH}) to choose the state they read. The IRIs carry
 * no version segment: the layout grows by adding graphs beside these, never by changing
 * them.</p>
 */
public interface VersioningGraphs {

  /** The prefix every graph IRI of the library starts with: {@value} */
  String PREFIX = "https://w3id.org/kognio/rdf/versioning/graph/";

  /**
   * The published view: a copy of each proxy IRI's current version, under the proxy IRI.
   * Its slice of a proxy IRI is replaced on publish.
   */
  IRI PUBLISHED = new SimpleIRI(PREFIX + "published");

  /**
   * The content store: every published content once, with its CID as subject. A version
   * is its CID's slice here. Named {@code content-store} so it is not confused with the
   * caller's content graph.
   */
  IRI CONTENT_STORE = new SimpleIRI(PREFIX + "content-store");

  /**
   * The history: one entry per publish and tombstones, plus the revision counter of each
   * proxy IRI (see {@link VocabVersioning}).
   */
  IRI HISTORY = new SimpleIRI(PREFIX + "history");

  /**
   * The config graph: settings of the versioned dataset, today the content graph it is
   * bound to ({@link VocabVersioning#CONTENT_GRAPH}).
   */
  IRI CONFIG = new SimpleIRI(PREFIX + "config");

  /** All graphs of this interface, in declaration order. */
  List<IRI> ALL = List.of(PUBLISHED, CONTENT_STORE, HISTORY, CONFIG);
}
