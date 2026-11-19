// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.IRI;

/**
 * Pins the IRIs of the library's graphs and vocabulary. Stored data binds them
 * (ADR-0023): a change here is a break of every versioned dataset, not a refactoring.
 */
class VersioningIRIsTest {

  @Test
  void graphIRIs() {
    assertThat(VersioningGraphs.ALL).extracting(IRI::getIRIString)
        .containsExactly("https://w3id.org/kognio/rdf/versioning/graph/published",
            "https://w3id.org/kognio/rdf/versioning/graph/content-store",
            "https://w3id.org/kognio/rdf/versioning/graph/history",
            "https://w3id.org/kognio/rdf/versioning/graph/config");
  }

  @Test
  void vocabularyIRIs() {
    assertThat(new IRI[] {VocabVersioning.ENTRY, VocabVersioning.REVISION, VocabVersioning.SEQUENCE,
        VocabVersioning.CID, VocabVersioning.PROCEDURE, VocabVersioning.CONTENT_GRAPH, VocabVersioning.DRAFT_FRAGMENT,
        VocabVersioning.PUBLISHED_FRAGMENT, VocabVersioning.VERSION_FRAGMENT}).extracting(IRI::getIRIString)
        .containsExactly("https://w3id.org/kognio/rdf/versioning#entry",
            "https://w3id.org/kognio/rdf/versioning#revision", "https://w3id.org/kognio/rdf/versioning#sequence",
            "https://w3id.org/kognio/rdf/versioning#cid", "https://w3id.org/kognio/rdf/versioning#procedure",
            "https://w3id.org/kognio/rdf/versioning#contentGraph",
            "https://w3id.org/kognio/rdf/versioning#draftFragment",
            "https://w3id.org/kognio/rdf/versioning#publishedFragment",
            "https://w3id.org/kognio/rdf/versioning#versionFragment");
  }
}
