// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.cid.CanonicalizationResourceLimitExceededException;
import io.kogn.rdf.cid.ContentAddressedIriGenerator;
import io.kogn.rdf.cid.ContentAddressedIriGeneratorSexpr;
import io.kogn.rdf.cid.ContentAddressingException;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleGraph;
import io.kogn.rdf.terms.SimpleIRI;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabXsd;

/**
 * The reads of a versioned dataset (ADR-0022, ADR-0023): published state, version by CID
 * and by sequence number, history and publish state, against a real {@code DatasetTx}
 * over an in-memory RDF4J store.
 */
class ResourceVersioningReadTest {

  private static final IRI CONTENT = new SimpleIRI("https://example.org/graph/content");
  private static final IRI A = new SimpleIRI("https://example.org/res/A");
  private static final IRI B = new SimpleIRI("https://example.org/res/B");
  private static final IRI A_PART = new SimpleIRI("https://example.org/res/A#part");
  private static final IRI P = new SimpleIRI("https://example.org/vocab/p");
  private static final IRI Q = new SimpleIRI("https://example.org/vocab/q");
  private static final IRI ALICE = new SimpleIRI("https://example.org/people/alice");
  private static final int HUBS = 36;
  private static final int LEAVES = 6;
  private static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");

  private final SimpleRdf rdf = new SimpleRdf();
  private final ContentAddressedIriGenerator generator = new ContentAddressedIriGeneratorSexpr();
  private Repository repository;
  private DatasetTransactor transactor;
  private ResourceVersioning versioning;

  @BeforeEach
  void setUp() {
    repository = new SailRepository(new MemoryStore());
    repository.init();
    transactor = new DatasetTransactorRdf4j(repository);
    versioning = new ResourceVersioningNamedGraphs(CONTENT, generator, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    repository.shutDown();
  }

  // --- readPublished

  @Test
  void aNeverPublishedProxyIriHasAnEmptyPublishedState() {
    draft(A, one(A, "one"));

    assertThat(transactor.inTransaction(tx -> versioning.readPublished(tx, A)).isEmpty()).isTrue();
  }

  @Test
  void thePublishedStateIsTheLastPublishedSliceAndIgnoresLaterDraftChanges() {
    final Graph first = one(A, "one");
    first.add(A_PART, P, rdf.createLiteral("part"));
    draft(A, first);
    publish(A);
    draft(A, one(A, "two"));

    final ReadableGraph published = transactor.inTransaction(tx -> copy(versioning.readPublished(tx, A)));

    assertThat(published.size()).isEqualTo(2);
    assertThat(published.contains(rdf.createTriple(A, P, rdf.createLiteral("one")))).isTrue();
    assertThat(published.contains(rdf.createTriple(A_PART, P, rdf.createLiteral("part")))).isTrue();
  }

  @Test
  void thePublishedStateOfOneProxyIriDoesNotHoldAnother() {
    draft(A, one(A, "a"));
    draft(B, one(B, "b"));
    publish(A, B);

    assertThat(transactor.inTransaction(tx -> copy(versioning.readPublished(tx, B))).size()).isEqualTo(1);
  }

  // --- readVersion by CID

  @Test
  void aVersionIsReadByItsCidWithTheCidAsSubject() {
    final Graph draft = one(A, "one");
    draft(A, draft);
    publish(A);
    final IRI cid = generator.generateIri(A, draft);

    final Optional<ReadableGraph> version = transactor
        .inTransaction(tx -> versioning.readVersion(tx, cid).map(this::copy));

    assertThat(version).isPresent();
    assertThat(version.get().size()).isEqualTo(1);
    assertThat(version.get().contains(rdf.createTriple(cid, P, rdf.createLiteral("one")))).isTrue();
  }

  @Test
  void aVersionWithBlankNodesStillVerifiesAgainstItsCid() {
    final BlankNode node = rdf.createBlankNode("n");
    final Graph draft = new SimpleGraph();
    draft.add(A, P, node);
    draft.add(node, Q, rdf.createLiteral("x"));
    draft(A, draft);
    publish(A);
    final IRI cid = generator.generateIri(A, draft);

    final ReadableGraph version = transactor.inTransaction(tx -> versioning.readVersion(tx, cid).map(this::copy))
        .orElseThrow();

    assertThat(version.size()).isEqualTo(2);
    assertThat(generator.generateIri(cid, version)).isEqualTo(cid);
  }

  @Test
  void aCidTheContentStoreDoesNotHoldIsEmpty() {
    draft(A, one(A, "one"));
    publish(A);
    final IRI unknown = generator.generateIri(A, one(A, "unpublished"));

    assertThat(versionOf(unknown)).isEmpty();
  }

  @Test
  void aCidInAnotherSpellingIsNotFound() {
    final Graph draft = one(A, "one");
    draft(A, draft);
    publish(A);
    final String canonical = generator.generateIri(A, draft).getIRIString();
    final IRI otherSpelling = new SimpleIRI(canonical.replace("ni:///", "ni://example.org/"));

    assertThat(versionOf(otherSpelling)).isEmpty();
  }

  @Test
  void aCidWithAFragmentIsRejected() {
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.readVersion(tx, A_PART)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aReferenceToAnotherProxyIriInsideAVersionKeepsNamingTheProxyIriOnEveryReadPath() {
    final Graph draft = one(A, "one");
    draft.add(A, Q, B);
    draft(A, draft);
    draft(B, one(B, "b"));
    publish(A, B);
    final IRI cid = generator.generateIri(A, draft);
    final IRI cidB = generator.generateIri(B, one(B, "b"));

    final ReadableGraph byCid = transactor.inTransaction(tx -> copy(versioning.readVersion(tx, cid).orElseThrow()));
    final ReadableGraph bySequence = transactor
        .inTransaction(tx -> copy(versioning.readVersion(tx, A, 1).orElseThrow()));
    final ReadableGraph published = transactor.inTransaction(tx -> copy(versioning.readPublished(tx, A)));

    for (final ReadableGraph version : List.of(byCid, bySequence)) {
      assertThat(version.contains(rdf.createTriple(cid, Q, B))).isTrue();
      assertThat(version.contains(rdf.createTriple(cid, Q, cidB))).isFalse();
    }
    assertThat(published.contains(rdf.createTriple(A, Q, B))).isTrue();
  }

  @Test
  void aSelfReferenceBecomesTheCidInAVersionAndStaysTheProxyIriInThePublishedState() {
    final Graph draft = one(A, "one");
    draft.add(A, Q, A);
    draft(A, draft);
    publish(A);
    final IRI cid = generator.generateIri(A, draft);

    final ReadableGraph byCid = transactor.inTransaction(tx -> copy(versioning.readVersion(tx, cid).orElseThrow()));
    final ReadableGraph bySequence = transactor
        .inTransaction(tx -> copy(versioning.readVersion(tx, A, 1).orElseThrow()));
    final ReadableGraph published = transactor.inTransaction(tx -> copy(versioning.readPublished(tx, A)));

    for (final ReadableGraph version : List.of(byCid, bySequence)) {
      assertThat(version.contains(rdf.createTriple(cid, Q, cid))).isTrue();
      assertThat(version.contains(rdf.createTriple(cid, Q, A))).isFalse();
    }
    assertThat(published.contains(rdf.createTriple(A, Q, A))).isTrue();
  }

  // --- readVersion by sequence number

  @Test
  void versionNOfAProxyIriIsTheContentOfItsNthEntry() {
    draft(A, one(A, "one"));
    publish(A);
    draft(A, one(A, "two"));
    publish(A);

    final IRI first = generator.generateIri(A, one(A, "one"));
    final IRI second = generator.generateIri(A, one(A, "two"));
    final ReadableGraph v1 = transactor.inTransaction(tx -> versioning.readVersion(tx, A, 1).map(this::copy))
        .orElseThrow();
    final ReadableGraph v2 = transactor.inTransaction(tx -> versioning.readVersion(tx, A, 2).map(this::copy))
        .orElseThrow();

    assertThat(v1.contains(rdf.createTriple(first, P, rdf.createLiteral("one")))).isTrue();
    assertThat(v2.contains(rdf.createTriple(second, P, rdf.createLiteral("two")))).isTrue();
  }

  @Test
  void aSequenceNumberTheProxyIriNeverReachedIsEmpty() {
    draft(A, one(A, "one"));
    publish(A);

    assertThat(versionOf(A, 2)).isEmpty();
    assertThat(versionOf(B, 1)).isEmpty();
  }

  @Test
  void anEntryWhoseContentIsNotInTheStoreYieldsEmpty() {
    draft(A, one(A, "one"));
    publish(A);
    final IRI cid = generator.generateIri(A, one(A, "one"));
    final Graph content = transactor.inTransaction(tx -> copy(tx.export(VersioningGraphs.CONTENT_STORE)));
    transactor.inTransaction(tx -> {
      tx.remove(VersioningGraphs.CONTENT_STORE, content);
      return null;
    });

    assertThat(content.contains(rdf.createTriple(cid, P, rdf.createLiteral("one")))).isTrue();
    assertThat(versionOf(A, 1)).isEmpty();
  }

  @Test
  void aSequenceNumberBelowOneAndAProxyIriWithAFragmentAreRejected() {
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.readVersion(tx, A, 0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.readVersion(tx, A_PART, 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aHistoryWithAGapIsAnIllegalStateForVersionN() {
    addGappedEntry();

    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.readVersion(tx, A, 2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gapless");
  }

  // --- history

  @Test
  void theHistoryOfANeverPublishedProxyIriIsEmpty() {
    assertThat(historyOf(A)).isEmpty();
  }

  @Test
  void theHistoryListsTheEntriesOldestFirstWithTheirMetadata() {
    draft(A, one(A, "one"));
    final IRI first = publish(PublishDetails.NONE.withCreator(ALICE).withComment("first cut"), A).entries().get(A);
    draft(A, one(A, "two"));
    final IRI second = publish(PublishDetails.NONE, A).entries().get(A);

    final List<HistoryEntry> history = transactor.inTransaction(tx -> versioning.history(tx, A));

    assertThat(history).hasSize(2);
    assertThat(history.get(0))
        .isEqualTo(new HistoryEntry(first, 1, EntryKind.PUBLISH, Optional.of(generator.generateIri(A, one(A, "one"))),
            Optional.of("ni-rdf/1"), NOW, Optional.of(ALICE), Optional.of("first cut")));
    assertThat(history.get(1))
        .isEqualTo(new HistoryEntry(second, 2, EntryKind.PUBLISH, Optional.of(generator.generateIri(A, one(A, "two"))),
            Optional.of("ni-rdf/1"), NOW, Optional.empty(), Optional.empty()));
  }

  @Test
  void theHistoryOfOneProxyIriDoesNotHoldAnotherOnes() {
    draft(A, one(A, "a"));
    draft(B, one(B, "b"));
    publish(A, B);

    assertThat(historyOf(B)).hasSize(1);
  }

  @Test
  void anEntryWithoutATimestampIsAnIllegalState() {
    addGappedEntryWithSequence(1);

    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.history(tx, A)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("history entry");
  }

  @Test
  void aHistoryWithADuplicateSequenceNumberIsAnIllegalState() {
    final Literal created = rdf.createLiteral("2026-10-31T09:00:00Z", VocabXsd.DATETIME);
    addEntry("1", 2, created);
    addEntry("2", 2, created);

    assertThatThrownBy(() -> historyOf(A)).isInstanceOf(IllegalStateException.class).hasMessageContaining("gapless");
    assertThatThrownBy(() -> versionOf(A, 1)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aHistoryWithAGapIsAnIllegalStateForThePublishState() {
    addGappedEntry();

    assertThatThrownBy(() -> state(A)).isInstanceOf(IllegalStateException.class).hasMessageContaining("gapless");
  }

  @Test
  void aHistoryEntryNeedsAPositiveSequenceNumberAndAllItsParts() {
    final IRI iri = new SimpleIRI("urn:uuid:00000000-0000-0000-0000-000000000009");
    assertThatThrownBy(() -> new HistoryEntry(iri, 0, EntryKind.PUBLISH, Optional.of(A), Optional.of("ni-rdf/1"), NOW,
        Optional.empty(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HistoryEntry(iri, 1, EntryKind.PUBLISH, null, Optional.of("ni-rdf/1"), NOW,
        Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new HistoryEntry(iri, 1, null, Optional.of(A), Optional.of("ni-rdf/1"), NOW,
        Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new HistoryEntry(iri, 1, EntryKind.PUBLISH, Optional.empty(), Optional.empty(), NOW,
        Optional.empty(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HistoryEntry(iri, 1, EntryKind.TOMBSTONE, Optional.of(A), Optional.empty(), NOW,
        Optional.empty(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aHistoryWithAGapIsAnIllegalStateForTheHistory() {
    addGappedEntry();

    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.history(tx, A)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gapless");
  }

  @Test
  void aTimestampThatIsNoDateTimeIsAnIllegalState() {
    addEntryWithCreated(rdf.createLiteral("yesterday"));

    assertThatThrownBy(() -> historyOf(A)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("malformed history entry");
  }

  @Test
  void aTimestampWithoutAZoneIsAnIllegalState() {
    addEntryWithCreated(rdf.createLiteral("2026-10-31T09:00:00", VocabXsd.DATETIME));

    assertThatThrownBy(() -> historyOf(A)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("malformed history entry");
  }

  // --- publishState

  @Test
  void aProxyIriWithoutHistoryIsNeverPublishedWhateverItsDraftHolds() {
    assertThat(state(A)).isEqualTo(PublishState.NEVER_PUBLISHED);
    draft(A, one(A, "one"));
    assertThat(state(A)).isEqualTo(PublishState.NEVER_PUBLISHED);
  }

  @Test
  void aDraftWithTheCidOfTheLatestEntryIsPublishedUnchanged() {
    draft(A, one(A, "one"));
    publish(A);

    assertThat(state(A)).isEqualTo(PublishState.PUBLISHED_UNCHANGED);
  }

  @Test
  void aDraftWithBlankNodesIsUnchangedAlthoughItsLabelsDifferFromTheStoredOnes() {
    final BlankNode node = rdf.createBlankNode("n");
    final Graph draft = new SimpleGraph();
    draft.add(A, P, node);
    draft.add(node, Q, rdf.createLiteral("x"));
    draft(A, draft);
    publish(A);

    assertThat(state(A)).isEqualTo(PublishState.PUBLISHED_UNCHANGED);
  }

  @Test
  void aChangedDraftHasUnpublishedChangesAndReturningToTheLatestVersionHasNone() {
    draft(A, one(A, "one"));
    publish(A);
    draft(A, one(A, "two"));
    assertThat(state(A)).isEqualTo(PublishState.PUBLISHED_WITH_UNPUBLISHED_CHANGES);

    publish(A);
    assertThat(state(A)).isEqualTo(PublishState.PUBLISHED_UNCHANGED);

    draft(A, one(A, "one"));
    assertThat(state(A)).isEqualTo(PublishState.PUBLISHED_WITH_UNPUBLISHED_CHANGES);
  }

  @Test
  void anEmptiedDraftOfAPublishedProxyIriHasUnpublishedChanges() {
    draft(A, one(A, "one"));
    publish(A);
    draft(A, new SimpleGraph());

    assertThat(state(A)).isEqualTo(PublishState.PUBLISHED_WITH_UNPUBLISHED_CHANGES);
  }

  @Test
  void aWritePastThePortIsSeenByItsContent() {
    draft(A, one(A, "one"));
    publish(A);
    assertThat(state(A)).isEqualTo(PublishState.PUBLISHED_UNCHANGED);
    inTx(tx -> {
      tx.remove(CONTENT, one(A, "one"));
      tx.add(CONTENT, one(A, "x"));
      return null;
    });

    assertThat(state(A)).isEqualTo(PublishState.PUBLISHED_WITH_UNPUBLISHED_CHANGES);

    inTx(tx -> {
      tx.remove(CONTENT, one(A, "x"));
      tx.add(CONTENT, one(A, "one"));
      return null;
    });

    assertThat(state(A)).isEqualTo(PublishState.PUBLISHED_UNCHANGED);
  }

  @Test
  void aLatestEntryOfAnotherProcedureCountsAsChanged() {
    draft(A, one(A, "one"));
    publish(A);
    final ResourceVersioning other = new ResourceVersioningNamedGraphs(CONTENT, new Tagged(generator, "ni-rdf/2"),
        Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(this.<PublishState>inTx(tx -> other.publishState(tx, A)))
        .isEqualTo(PublishState.PUBLISHED_WITH_UNPUBLISHED_CHANGES);
  }

  @Test
  void aDraftOverTheCostLimitIsReported() {
    draft(A, one(A, "one"));
    publish(A);
    final Graph dense = new SimpleGraph();
    for (int h = 0; h < HUBS; h++) {
      final BlankNode hub = rdf.createBlankNode("h" + h);
      dense.add(A, P, hub);
      for (int l = 0; l < LEAVES; l++) {
        dense.add(hub, Q, rdf.createBlankNode("l" + h + "_" + l));
      }
    }
    draft(A, dense);

    assertThatThrownBy(() -> state(A)).isInstanceOf(CanonicalizationResourceLimitExceededException.class);
  }

  @Test
  void aFailureToDeriveTheIdentifierIsReported() {
    draft(A, one(A, "one"));
    publish(A);
    final ResourceVersioning failing = new ResourceVersioningNamedGraphs(CONTENT,
        new Failing(new ContentAddressingException("cannot derive", null)), Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> transactor.inTransaction(tx -> failing.publishState(tx, A)))
        .isInstanceOf(ContentAddressingException.class);
  }

  @Test
  void aDraftThatViolatesAPreconditionOfTheGeneratorIsReported() {
    draft(A, one(A, "one"));
    publish(A);
    final ResourceVersioning refusing = new ResourceVersioningNamedGraphs(CONTENT,
        new Failing(new IllegalArgumentException("refused")), Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> transactor.inTransaction(tx -> refusing.publishState(tx, A)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("refused");
  }

  @Test
  void aProxyIriWithAFragmentIsRejectedByEveryRead() {
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.readPublished(tx, A_PART)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.history(tx, A_PART)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.publishState(tx, A_PART)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aReadOfAnotherContentGraphIsRejected() {
    draft(A, one(A, "one"));
    final ResourceVersioning other = new ResourceVersioningNamedGraphs(
        new SimpleIRI("https://example.org/graph/other"));

    assertThatThrownBy(() -> transactor.inTransaction(tx -> other.history(tx, A)))
        .isInstanceOf(ContentGraphMismatchException.class);
  }

  @Test
  void aNullArgumentIsRejected() {
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.readPublished(tx, null)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.readVersion(tx, (IRI) null)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.history(tx, null)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.publishState(tx, null)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> versioning.publishState(null, A)).isInstanceOf(NullPointerException.class);
  }

  // --- helpers

  private <T> T inTx(final Function<DatasetTx, T> work) {
    return transactor.inTransaction(work);
  }

  private Optional<ReadableGraph> versionOf(final IRI cid) {
    return transactor.inTransaction(tx -> versioning.readVersion(tx, cid));
  }

  private Optional<ReadableGraph> versionOf(final IRI proxy, final long sequence) {
    return transactor.inTransaction(tx -> versioning.readVersion(tx, proxy, sequence));
  }

  private List<HistoryEntry> historyOf(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.history(tx, proxy));
  }

  private PublishState state(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.publishState(tx, proxy));
  }

  private void addGappedEntry() {
    addGappedEntryWithSequence(2);
  }

  private void addGappedEntryWithSequence(final long sequence) {
    addEntry(sequence, null);
  }

  private void addEntryWithCreated(final Literal created) {
    addEntry(1, created);
  }

  private void addEntry(final long sequence, final Literal created) {
    addEntry("1", sequence, created);
  }

  private void addEntry(final String id, final long sequence, final Literal created) {
    final IRI entryIri = new SimpleIRI("urn:uuid:00000000-0000-0000-0000-00000000000" + id);
    final Graph entry = new SimpleGraph();
    entry.add(A, VocabVersioning.ENTRY, entryIri);
    entry.add(entryIri, VocabVersioning.SEQUENCE, rdf.createLiteral(Long.toString(sequence), VocabXsd.INTEGER));
    entry.add(entryIri, VocabVersioning.CONTENT, new SimpleIRI("ni:///sha3-256;x"));
    entry.add(entryIri, VocabVersioning.PROCEDURE, rdf.createLiteral("ni-rdf/1"));
    if (created != null) {
      entry.add(entryIri, VocabDct.CREATED, created);
    }
    transactor.inTransaction(tx -> {
      tx.add(VersioningGraphs.HISTORY, entry);
      return null;
    });
  }

  private Graph one(final IRI proxy, final String value) {
    final Graph graph = new SimpleGraph();
    graph.add(proxy, P, rdf.createLiteral(value));
    return graph;
  }

  private void draft(final IRI proxy, final ReadableGraph content) {
    transactor
        .inTransaction(tx -> versioning.replaceSlice(tx, proxy, content, versioning.readSlice(tx, proxy).token()));
  }

  private PublishResult publish(final IRI... proxies) {
    return publish(PublishDetails.NONE, proxies);
  }

  private PublishResult publish(final PublishDetails details, final IRI... proxies) {
    return transactor.inTransaction(tx -> versioning.publish(tx,
        List.of(proxies).stream().map(proxy -> versioning.readSlice(tx, proxy).token()).toList(), details));
  }

  private Graph copy(final ReadableGraph graph) {
    final Graph copy = new SimpleGraph();
    for (final Triple triple : graph.stream().toList()) {
      copy.add(triple);
    }
    return copy;
  }

  /** A generator with another procedure tag, deriving the same names. */
  private record Tagged(ContentAddressedIriGenerator delegate, String tag) implements ContentAddressedIriGenerator {

    @Override
    public IRI generateIri(final IRI base, final ReadableGraph graph) {
      return delegate.generateIri(base, graph);
    }

    @Override
    public String procedureTag() {
      return tag;
    }
  }

  /** A generator that refuses every draft with the given exception. */
  private record Failing(RuntimeException failure) implements ContentAddressedIriGenerator {

    @Override
    public IRI generateIri(final IRI base, final ReadableGraph graph) {
      throw failure;
    }

    @Override
    public String procedureTag() {
      return "ni-rdf/1";
    }
  }
}
