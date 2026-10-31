// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.List;
import java.util.Objects;

import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleGraph;
import io.kogn.rdf.terms.Triple;

/**
 * The implementation of {@link ResourceVersioning}: plain logic over a {@link DatasetTx},
 * keeping a versioned dataset in the fixed named graphs of {@link VersioningGraphs}
 * (ADR-0023).
 *
 * <p>Stateless apart from the content graph it is bound to, and safe for concurrent use;
 * concurrency control is the transaction's.</p>
 */
public final class ResourceVersioningNamedGraphs implements ResourceVersioning {

  private final IRI contentGraph;

  /**
   * Creates the versioning of the dataset whose content graph is {@code contentGraph}.
   *
   * <p>Nothing is read or written here; the binding is recorded or checked by the first
   * operation (see {@link #bindContentGraph(DatasetTx)}).</p>
   *
   * @param contentGraph the caller's content graph, the draft view; must not be
   *     {@code null} and must not be one of {@link VersioningGraphs#ALL}
   * @throws NullPointerException if {@code contentGraph} is {@code null}
   * @throws IllegalArgumentException if {@code contentGraph} is a graph of the library
   */
  public ResourceVersioningNamedGraphs(final IRI contentGraph) {
    Objects.requireNonNull(contentGraph, "contentGraph");
    if (VersioningGraphs.ALL.contains(contentGraph)) {
      throw new IllegalArgumentException(
          "content graph must not be a graph of the library: " + contentGraph.ntriplesString());
    }
    this.contentGraph = contentGraph;
  }

  @Override
  public IRI contentGraph() {
    return contentGraph;
  }

  @Override
  public void bindContentGraph(final DatasetTx tx) {
    Objects.requireNonNull(tx, "tx");
    final IRI config = VersioningGraphs.CONFIG;
    // First-insert guard through contains, not ASK (ADR-0008, ADR-0022): with a fixed
    // subject and predicate the backend answers it from its own pattern lookup.
    if (!tx.contains(config, config, VocabVersioning.CONTENT_GRAPH, null)) {
      final Graph binding = new SimpleGraph();
      binding.add(config, VocabVersioning.CONTENT_GRAPH, contentGraph);
      tx.add(config, binding);
      return;
    }
    final List<RDFTerm> recorded = tx.export(config)
        .stream(config, VocabVersioning.CONTENT_GRAPH, null)
        .map(Triple::getObject)
        .toList();
    if (recorded.size() != 1 || !contentGraph.equals(recorded.getFirst())) {
      throw new ContentGraphMismatchException(contentGraph, recorded);
    }
  }
}
