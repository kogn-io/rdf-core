// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.kogn.rdf.cid.BaseRebaser;
import io.kogn.rdf.cid.ContentAddressedIriGenerator;
import io.kogn.rdf.cid.ContentAddressedIriGeneratorSexpr;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleGraph;
import io.kogn.rdf.terms.SimpleIRI;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;
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

  private static final String ENTRY_IRI_PREFIX = "urn:uuid:";

  private final IRI contentGraph;
  private final ContentAddressedIriGenerator generator;
  private final Clock clock;
  private final BaseRebaser rebaser = new BaseRebaser(new SimpleRdf());

  /**
   * Creates the versioning of the dataset whose content graph is {@code contentGraph},
   * with the {@code ni-rdf/1} generator and the system clock.
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
    this(contentGraph, new ContentAddressedIriGeneratorSexpr(), Clock.systemUTC());
  }

  /**
   * Creates the versioning of the dataset whose content graph is {@code contentGraph},
   * deriving CIDs with {@code generator} and stamping history entries from {@code clock}.
   *
   * <p>Nothing is read or written here. All instances working on one dataset must use the
   * same procedure: the CIDs of its versions are compared with each other.</p>
   *
   * @param contentGraph the caller's content graph, the draft view; must not be
   *     {@code null} and must not be one of {@link VersioningGraphs#ALL}
   * @param generator derives the CID of a draft; must not be {@code null}
   * @param clock the source of {@code dcterms:created}; must not be {@code null}
   * @throws NullPointerException if an argument is {@code null}
   * @throws IllegalArgumentException if {@code contentGraph} is a graph of the library
   */
  public ResourceVersioningNamedGraphs(final IRI contentGraph, final ContentAddressedIriGenerator generator,
      final Clock clock) {
    Objects.requireNonNull(contentGraph, "contentGraph");
    this.generator = Objects.requireNonNull(generator, "generator");
    this.clock = Objects.requireNonNull(clock, "clock");
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
    final long next = raiseCounter(tx, proxyIri, counter, current);
    return new RevisionToken(proxyIri, next);
  }

  @Override
  public PublishResult publish(final DatasetTx tx, final Collection<RevisionToken> expected,
      final PublishDetails details) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(details, "details");
    bindContentGraph(tx);
    final List<PublishPlan> plans = new ArrayList<>();
    final Set<IRI> proxies = new HashSet<>();
    for (final RevisionToken token : expected) {
      Objects.requireNonNull(token, "expected must not hold null");
      if (!proxies.add(token.proxyIri())) {
        throw new IllegalArgumentException("two tokens for " + token.proxyIri().ntriplesString());
      }
      plans.add(planPublish(tx, token));
    }
    final Map<IRI, IRI> entries = new LinkedHashMap<>();
    final Map<IRI, RevisionToken> tokens = new LinkedHashMap<>();
    for (final PublishPlan plan : plans) {
      if (plan.unchanged()) {
        entries.put(plan.proxyIri(), plan.last().orElseThrow().iri());
        tokens.put(plan.proxyIri(), plan.token());
        continue;
      }
      final IRI entry = apply(tx, plan, details);
      entries.put(plan.proxyIri(), entry);
      tokens.put(plan.proxyIri(), new RevisionToken(plan.proxyIri(), plan.current() + 1));
    }
    return new PublishResult(entries, tokens);
  }

  @Override
  public ReadableGraph readPublished(final DatasetTx tx, final IRI proxyIri) {
    Objects.requireNonNull(tx, "tx");
    requireProxy(proxyIri);
    bindContentGraph(tx);
    return sliceOf(tx.export(VersioningGraphs.PUBLISHED), proxyIri);
  }

  @Override
  public Optional<ReadableGraph> readVersion(final DatasetTx tx, final IRI cid) {
    Objects.requireNonNull(tx, "tx");
    requireProxy(cid);
    bindContentGraph(tx);
    return versionOf(tx, cid);
  }

  @Override
  public Optional<ReadableGraph> readVersion(final DatasetTx tx, final IRI proxyIri, final long sequence) {
    Objects.requireNonNull(tx, "tx");
    requireProxy(proxyIri);
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be at least 1: " + sequence);
    }
    bindContentGraph(tx);
    return entriesOf(tx, proxyIri).stream()
        .filter(entry -> entry.sequence() == sequence && !entry.tombstone())
        .findFirst()
        .flatMap(StoredEntry::content)
        .flatMap(cid -> versionOf(tx, cid));
  }

  @Override
  public List<HistoryEntry> history(final DatasetTx tx, final IRI proxyIri) {
    Objects.requireNonNull(tx, "tx");
    requireProxy(proxyIri);
    bindContentGraph(tx);
    final List<StoredEntry> entries = entriesOf(tx, proxyIri);
    if (entries.isEmpty()) {
      return List.of();
    }
    final ReadableGraph history = tx.export(VersioningGraphs.HISTORY);
    return entries.stream().map(entry -> describe(history, entry)).toList();
  }

  @Override
  public PublishState publishState(final DatasetTx tx, final IRI proxyIri) {
    Objects.requireNonNull(tx, "tx");
    requireProxy(proxyIri);
    bindContentGraph(tx);
    final Optional<StoredEntry> last = lastEntry(tx, proxyIri);
    if (last.isEmpty()) {
      return PublishState.NEVER_PUBLISHED;
    }
    if (last.get().tombstone()) {
      return PublishState.DELETED;
    }
    final Graph slice = sliceOf(tx.export(contentGraph), proxyIri);
    if (slice.isEmpty()) {
      return PublishState.PUBLISHED_WITH_UNPUBLISHED_CHANGES;
    }
    return isUnchanged(last, generator.generateIri(proxyIri, slice))
        ? PublishState.PUBLISHED_UNCHANGED
        : PublishState.PUBLISHED_WITH_UNPUBLISHED_CHANGES;
  }

  @Override
  public DeleteResult delete(final DatasetTx tx, final IRI proxyIri, final RevisionToken expected,
      final PublishDetails details) {
    Objects.requireNonNull(tx, "tx");
    requireProxy(proxyIri);
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(details, "details");
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
    final ReadableGraph stored = tx.export(contentGraph);
    final Graph draft = sliceOf(stored, proxyIri);
    requireNoSharedBlankNode(new SimpleGraph(), stored, draft, proxyIri);
    final List<StoredEntry> entries = entriesOf(tx, proxyIri);
    final Optional<StoredEntry> last = entries.isEmpty() ? Optional.empty() : Optional.of(entries.getLast());
    if (draft.isEmpty() && last.isPresent() && last.get().tombstone()) {
      return new DeleteResult(last.get().iri(), expected);
    }
    if (!draft.isEmpty()) {
      tx.remove(contentGraph, draft);
    }
    final Graph published = sliceOf(tx.export(VersioningGraphs.PUBLISHED), proxyIri);
    if (!published.isEmpty()) {
      tx.remove(VersioningGraphs.PUBLISHED, published);
    }
    final Optional<StoredEntry> lastVersion = entries.reversed()
        .stream()
        .filter(e -> e.content().isPresent())
        .findFirst();
    final IRI entry = writeEntry(tx, last, proxyIri, EntryKind.TOMBSTONE, lastVersion.flatMap(StoredEntry::content),
        lastVersion.flatMap(StoredEntry::procedure), details);
    return new DeleteResult(entry, new RevisionToken(proxyIri, raiseCounter(tx, proxyIri, counter, current)));
  }

  private static Optional<ReadableGraph> versionOf(final DatasetTx tx, final IRI cid) {
    final Graph version = sliceOf(tx.export(VersioningGraphs.CONTENT_STORE), cid);
    return version.isEmpty() ? Optional.empty() : Optional.of(version);
  }

  private static HistoryEntry describe(final ReadableGraph history, final StoredEntry stored) {
    final RDFTerm created = single(history, stored.iri(), VocabDct.CREATED);
    final Instant instant;
    try {
      if (!(created instanceof Literal literal && VocabXsd.DATETIME.equals(literal.getDatatype()))) {
        throw new IllegalStateException("malformed history entry " + stored.iri().ntriplesString());
      }
      instant = Instant.parse(literal.getLexicalForm());
    } catch (final DateTimeParseException e) {
      throw new IllegalStateException("malformed history entry " + stored.iri().ntriplesString(), e);
    }
    final Optional<IRI> creator = optional(history, stored.iri(), VocabDct.CREATOR)
        .map(term -> asIri(term, stored.iri()));
    final Optional<String> comment = optional(history, stored.iri(), VocabDct.DESCRIPTION)
        .map(term -> asLexicalForm(term, stored.iri()));
    return new HistoryEntry(stored.iri(), stored.sequence(),
        stored.tombstone() ? EntryKind.TOMBSTONE : EntryKind.PUBLISH, stored.content(), stored.procedure(), instant,
        creator, comment);
  }

  private static Optional<RDFTerm> optional(final ReadableGraph history, final IRI entry, final IRI predicate) {
    final List<RDFTerm> found = history.stream(entry, predicate, null).map(Triple::getObject).toList();
    if (found.size() > 1) {
      throw new IllegalStateException(
          "history entry " + entry.ntriplesString() + " holds " + found.size() + " " + predicate.ntriplesString());
    }
    return found.stream().findFirst();
  }

  private static IRI asIri(final RDFTerm term, final IRI entry) {
    if (term instanceof IRI iri) {
      return iri;
    }
    throw new IllegalStateException("malformed history entry " + entry.ntriplesString());
  }

  private static String asLexicalForm(final RDFTerm term, final IRI entry) {
    if (term instanceof Literal literal) {
      return literal.getLexicalForm();
    }
    throw new IllegalStateException("malformed history entry " + entry.ntriplesString());
  }

  /** Everything read and derived for one proxy IRI; nothing is written while planning. */
  private PublishPlan planPublish(final DatasetTx tx, final RevisionToken token) {
    final IRI proxyIri = token.proxyIri();
    final Optional<Triple> counter = revisionOf(tx, proxyIri);
    final long current = counter.map(ResourceVersioningNamedGraphs::revisionValue).orElse(0L);
    if (current != token.revision()) {
      throw new RevisionConflictException(proxyIri);
    }
    final Graph slice = sliceOf(tx.export(contentGraph), proxyIri);
    final IRI cid = generator.generateIri(proxyIri, slice);
    final Optional<StoredEntry> last = lastEntry(tx, proxyIri);
    return new PublishPlan(token, current, counter, slice, cid, last, isUnchanged(last, cid));
  }

  private IRI apply(final DatasetTx tx, final PublishPlan plan, final PublishDetails details) {
    final IRI proxyIri = plan.proxyIri();
    final IRI cid = plan.cid();
    final Graph version = rebaser.rebase(plan.slice(), proxyIri, cid);
    if (!isStored(tx, version)) {
      tx.add(VersioningGraphs.CONTENT_STORE, withFreshBlankNodes(version));
    }
    final Graph oldPublished = sliceOf(tx.export(VersioningGraphs.PUBLISHED), proxyIri);
    if (!oldPublished.isEmpty()) {
      tx.remove(VersioningGraphs.PUBLISHED, oldPublished);
    }
    tx.add(VersioningGraphs.PUBLISHED, withFreshBlankNodes(plan.slice()));

    final IRI entry = writeEntry(tx, plan.last(), proxyIri, EntryKind.PUBLISH, Optional.of(cid),
        Optional.of(generator.procedureTag()), details);
    raiseCounter(tx, proxyIri, plan.counter(), plan.current());
    return entry;
  }

  /** Writes the next history entry of the proxy IRI and returns its IRI. */
  private IRI writeEntry(final DatasetTx tx, final Optional<StoredEntry> last, final IRI proxyIri, final EntryKind kind,
      final Optional<IRI> content, final Optional<String> procedure, final PublishDetails details) {
    final SimpleRdf rdf = new SimpleRdf();
    final long sequence = last.map(entry -> entry.sequence() + 1).orElse(1L);
    final IRI entry = new SimpleIRI(ENTRY_IRI_PREFIX + UUID.randomUUID());
    final Graph written = new SimpleGraph();
    written.add(proxyIri, VocabVersioning.ENTRY, entry);
    if (kind == EntryKind.TOMBSTONE) {
      written.add(entry, VocabRdf.TYPE, VocabVersioning.TOMBSTONE);
    }
    written.add(entry, VocabVersioning.SEQUENCE, rdf.createLiteral(Long.toString(sequence), VocabXsd.INTEGER));
    content.ifPresent(cid -> written.add(entry, VocabVersioning.CONTENT, cid));
    procedure.ifPresent(tag -> written.add(entry, VocabVersioning.PROCEDURE, rdf.createLiteral(tag)));
    written.add(entry, VocabDct.CREATED,
        rdf.createLiteral(clock.instant().truncatedTo(ChronoUnit.MILLIS).toString(), VocabXsd.DATETIME));
    details.creator().ifPresent(creator -> written.add(entry, VocabDct.CREATOR, creator));
    details.comment().ifPresent(comment -> written.add(entry, VocabDct.DESCRIPTION, rdf.createLiteral(comment)));
    tx.add(VersioningGraphs.HISTORY, written);
    return entry;
  }

  /**
   * Whether the version is in the content store already. The same CID always means the same
   * content, so one IRI subject of the version, found with {@code contains} (ADR-0008),
   * proves the copy: the CID itself, or a fragment IRI of it when the draft has no triple
   * on the proxy IRI.
   */
  private static boolean isStored(final DatasetTx tx, final Graph version) {
    final IRI subject = version.stream()
        .map(Triple::getSubject)
        .filter(IRI.class::isInstance)
        .map(IRI.class::cast)
        .findFirst()
        .orElseThrow();
    return tx.contains(VersioningGraphs.CONTENT_STORE, subject, null, null);
  }

  /** Whether the latest entry names this CID, derived by the procedure in use. */
  private boolean isUnchanged(final Optional<StoredEntry> last, final IRI cid) {
    return last.isPresent() && !last.get().tombstone() && last.get().content().equals(Optional.of(cid))
        && last.get().procedure().equals(Optional.of(generator.procedureTag()));
  }

  /** The entry with the highest sequence number of the proxy IRI, if it has any. */
  private static Optional<StoredEntry> lastEntry(final DatasetTx tx, final IRI proxyIri) {
    final List<StoredEntry> entries = entriesOf(tx, proxyIri);
    return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getLast());
  }

  /** All entries of the proxy IRI by ascending sequence number, checked to be gapless from 1. */
  private static List<StoredEntry> entriesOf(final DatasetTx tx, final IRI proxyIri) {
    if (!tx.contains(VersioningGraphs.HISTORY, proxyIri, VocabVersioning.ENTRY, null)) {
      return List.of();
    }
    final ReadableGraph history = tx.export(VersioningGraphs.HISTORY);
    final List<RDFTerm> linked = history.stream(proxyIri, VocabVersioning.ENTRY, null).map(Triple::getObject).toList();
    final List<StoredEntry> entries = new ArrayList<>();
    for (final RDFTerm term : linked) {
      if (!(term instanceof IRI entry)) {
        throw new IllegalStateException(
            "entry of " + proxyIri.ntriplesString() + " is not an IRI: " + term.ntriplesString());
      }
      entries.add(readEntry(history, entry));
    }
    entries.sort(Comparator.comparingLong(StoredEntry::sequence));
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).sequence() != i + 1) {
        throw new IllegalStateException("history of " + proxyIri.ntriplesString() + " is not gapless: " + linked.size()
            + " entries, sequence numbers " + entries.stream().map(StoredEntry::sequence).toList());
      }
    }
    return entries;
  }

  private static StoredEntry readEntry(final ReadableGraph history, final IRI entry) {
    final RDFTerm sequence = single(history, entry, VocabVersioning.SEQUENCE);
    final boolean tombstone = history.stream(entry, VocabRdf.TYPE, VocabVersioning.TOMBSTONE).findAny().isPresent();
    final Optional<RDFTerm> content = optional(history, entry, VocabVersioning.CONTENT);
    final Optional<RDFTerm> procedure = optional(history, entry, VocabVersioning.PROCEDURE);
    // A publish names its version and the procedure; a tombstone names both or neither.
    final boolean complete = content.isPresent() == procedure.isPresent() && (tombstone || content.isPresent());
    if (complete && sequence instanceof Literal literal && VocabXsd.INTEGER.equals(literal.getDatatype())
        && content.map(IRI.class::isInstance).orElse(true) && procedure.map(Literal.class::isInstance).orElse(true)) {
      try {
        final long number = Long.parseLong(literal.getLexicalForm());
        if (number >= 1) {
          return new StoredEntry(entry, number, tombstone, content.map(IRI.class::cast),
              procedure.map(term -> ((Literal) term).getLexicalForm()));
        }
      } catch (final NumberFormatException e) {
        // falls through to the malformed-entry error
      }
    }
    throw new IllegalStateException("malformed history entry " + entry.ntriplesString());
  }

  private static RDFTerm single(final ReadableGraph history, final IRI entry, final IRI predicate) {
    final List<RDFTerm> found = history.stream(entry, predicate, null).map(Triple::getObject).toList();
    if (found.size() != 1) {
      throw new IllegalStateException(
          "history entry " + entry.ntriplesString() + " holds " + found.size() + " " + predicate.ntriplesString());
    }
    return found.getFirst();
  }

  /** Replaces the counter triple, if any, by the next value and returns that value. */
  private static long raiseCounter(final DatasetTx tx, final IRI proxyIri, final Optional<Triple> counter,
      final long current) {
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
    return next;
  }

  /**
   * Copies {@code graph} with every blank node replaced by a fresh one. Stores share blank
   * node labels across the graphs of a dataset, so a version must not reuse the draft's
   * nodes: two versions of one draft would merge their descriptions of the same node.
   */
  private static Graph withFreshBlankNodes(final ReadableGraph graph) {
    final SimpleRdf rdf = new SimpleRdf();
    final Map<BlankNode, BlankNode> fresh = new HashMap<>();
    final Graph copy = new SimpleGraph();
    for (final Triple triple : graph.stream().toList()) {
      final BlankNodeOrIRI subject = triple.getSubject() instanceof BlankNode node
          ? fresh.computeIfAbsent(node, n -> rdf.createBlankNode())
          : triple.getSubject();
      final RDFTerm object = triple.getObject() instanceof BlankNode node
          ? fresh.computeIfAbsent(node, n -> rdf.createBlankNode())
          : triple.getObject();
      copy.add(subject, triple.getPredicate(), object);
    }
    return copy;
  }

  private record StoredEntry(IRI iri, long sequence, boolean tombstone, Optional<IRI> content,
      Optional<String> procedure) {
  }

  private record PublishPlan(RevisionToken token, long current, Optional<Triple> counter, Graph slice, IRI cid,
      Optional<StoredEntry> last, boolean unchanged) {

    IRI proxyIri() {
      return token.proxyIri();
    }
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
