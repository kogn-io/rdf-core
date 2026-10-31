// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabXsd;

/**
 * The implementation of {@link ResourceVersioning}: plain logic over a {@link DatasetTx},
 * keeping a versioned dataset in the fixed named graphs of {@link VersioningGraphs}
 * (ADR-0023).
 *
 * <p>Stateless apart from the content graph it is bound to, and safe for concurrent use;
 * concurrency control is the transaction's.</p>
 */
public final class ResourceVersioningNamedGraphs implements ResourceVersioning {

  private static final char FRAGMENT_SEPARATOR = '#';

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

  @Override
  public DraftSlice readSlice(final DatasetTx tx, final IRI proxyIri) {
    Objects.requireNonNull(tx, "tx");
    requireProxy(proxyIri);
    bindContentGraph(tx);
    final long revision = revisionOf(tx, proxyIri).map(ResourceVersioningNamedGraphs::revisionValue).orElse(0L);
    final Graph slice = sliceOf(tx.export(contentGraph), proxyIri);
    return new DraftSlice(proxyIri, slice, new RevisionToken(proxyIri, revision));
  }

  @Override
  public RevisionToken replaceSlice(final DatasetTx tx, final IRI proxyIri, final ReadableGraph content,
      final RevisionToken expected) {
    Objects.requireNonNull(tx, "tx");
    requireProxy(proxyIri);
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(expected, "expected");
    if (!proxyIri.equals(expected.proxyIri())) {
      throw new IllegalArgumentException(
          "token belongs to " + expected.proxyIri().ntriplesString() + ", not to " + proxyIri.ntriplesString());
    }
    bindContentGraph(tx);
    final Optional<Triple> counter = revisionOf(tx, proxyIri);
    final long current = counter.map(ResourceVersioningNamedGraphs::revisionValue).orElse(0L);
    if (current != expected.revision()) {
      throw new RevisionConflictException(proxyIri);
    }
    final Graph copy = copyOf(content);
    requireSlice(copy, proxyIri);

    final ReadableGraph stored = tx.export(contentGraph);
    final Graph old = sliceOf(stored, proxyIri);
    requireNoSharedBlankNode(copy, stored, old, proxyIri);

    if (!old.isEmpty()) {
      tx.remove(contentGraph, old);
    }
    if (!copy.isEmpty()) {
      tx.add(contentGraph, copy);
    }
    final long next = current + 1;
    counter.ifPresent(triple -> {
      final Graph previous = new SimpleGraph();
      previous.add(triple);
      tx.remove(VersioningGraphs.HISTORY, previous);
    });
    final Graph raised = new SimpleGraph();
    final Literal value = new SimpleRdf().createLiteral(Long.toString(next), VocabXsd.INTEGER);
    raised.add(proxyIri, VocabVersioning.REVISION, value);
    tx.add(VersioningGraphs.HISTORY, raised);
    return new RevisionToken(proxyIri, next);
  }

  private static void requireProxy(final IRI proxyIri) {
    Objects.requireNonNull(proxyIri, "proxyIri");
    if (proxyIri.getIRIString().indexOf(FRAGMENT_SEPARATOR) >= 0) {
      throw new IllegalArgumentException("proxy IRI must not contain a fragment: " + proxyIri.ntriplesString());
    }
  }

  /** The counter triple of the proxy IRI, read through {@code contains} first (ADR-0008). */
  private static Optional<Triple> revisionOf(final DatasetTx tx, final IRI proxyIri) {
    if (!tx.contains(VersioningGraphs.HISTORY, proxyIri, VocabVersioning.REVISION, null)) {
      return Optional.empty();
    }
    final List<Triple> found = tx.export(VersioningGraphs.HISTORY)
        .stream(proxyIri, VocabVersioning.REVISION, null)
        .toList();
    if (found.size() != 1) {
      throw new IllegalStateException(
          "history graph holds " + found.size() + " revision counters for " + proxyIri.ntriplesString());
    }
    return Optional.of(found.getFirst());
  }

  private static long revisionValue(final Triple counter) {
    if (counter.getObject() instanceof Literal literal && VocabXsd.INTEGER.equals(literal.getDatatype())) {
      try {
        final long value = Long.parseLong(literal.getLexicalForm());
        if (value >= 0) {
          return value;
        }
      } catch (final NumberFormatException e) {
        // falls through to the malformed-counter error
      }
    }
    throw new IllegalStateException("malformed revision counter: " + counter.getObject().ntriplesString());
  }

  private static boolean isMember(final BlankNodeOrIRI subject, final String proxy) {
    if (!(subject instanceof IRI iri)) {
      return false;
    }
    final String text = iri.getIRIString();
    return text.equals(proxy) || text.startsWith(proxy + FRAGMENT_SEPARATOR);
  }

  /** The slice of {@code proxyIri} in {@code graph}: members, plus the blank-node closure. */
  private static Graph sliceOf(final ReadableGraph graph, final IRI proxyIri) {
    final String proxy = proxyIri.getIRIString();
    final Graph slice = new SimpleGraph();
    final Deque<BlankNode> pending = new ArrayDeque<>();
    final Set<BlankNode> seen = new HashSet<>();
    for (final Triple triple : graph.stream().filter(t -> isMember(t.getSubject(), proxy)).toList()) {
      slice.add(triple);
      enqueue(triple.getObject(), seen, pending);
    }
    while (!pending.isEmpty()) {
      final BlankNode node = pending.poll();
      for (final Triple triple : graph.stream(node, null, null).toList()) {
        slice.add(triple);
        enqueue(triple.getObject(), seen, pending);
      }
    }
    return slice;
  }

  private static void enqueue(final RDFTerm object, final Set<BlankNode> seen, final Deque<BlankNode> pending) {
    if (object instanceof BlankNode node && seen.add(node)) {
      pending.add(node);
    }
  }

  private static Graph copyOf(final ReadableGraph content) {
    final Graph copy = new SimpleGraph();
    for (final Triple triple : content.stream().toList()) {
      copy.add(triple);
    }
    return copy;
  }

  private static void requireSlice(final Graph content, final IRI proxyIri) {
    final Graph slice = sliceOf(content, proxyIri);
    if (slice.size() == content.size()) {
      return;
    }
    final Triple stray = content.stream().filter(t -> !slice.contains(t)).findFirst().orElseThrow();
    throw new IllegalArgumentException(
        "triple is not part of the slice of " + proxyIri.ntriplesString() + ": " + stray.getSubject().ntriplesString()
            + " " + stray.getPredicate().ntriplesString() + " " + stray.getObject().ntriplesString());
  }

  private static void requireNoSharedBlankNode(final Graph content, final ReadableGraph stored, final Graph old,
      final IRI proxyIri) {
    final Set<BlankNode> nodes = new HashSet<>();
    collectBlankNodes(content, nodes);
    collectBlankNodes(old, nodes);
    if (nodes.isEmpty()) {
      return;
    }
    for (final Triple triple : stored.stream().toList()) {
      if (old.contains(triple)) {
        continue;
      }
      if (nodes.contains(triple.getSubject()) || nodes.contains(triple.getObject())) {
        throw new AmbiguousBlankNodeException("blank node of the slice of " + proxyIri.ntriplesString()
            + " is also used outside it, in " + triple.getSubject().ntriplesString() + " "
            + triple.getPredicate().ntriplesString() + " " + triple.getObject().ntriplesString());
      }
    }
  }

  private static void collectBlankNodes(final ReadableGraph graph, final Set<BlankNode> nodes) {
    for (final Triple triple : graph.stream().toList()) {
      if (triple.getSubject() instanceof BlankNode node) {
        nodes.add(node);
      }
      if (triple.getObject() instanceof BlankNode node) {
        nodes.add(node);
      }
    }
  }
}
