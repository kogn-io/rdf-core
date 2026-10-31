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

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.cid.ContentAddressedIriGenerator;
import io.kogn.rdf.cid.ContentAddressedIriGeneratorSexpr;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleGraph;
import io.kogn.rdf.terms.SimpleIRI;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabXsd;

/**
 * The tombstone step (ADR-0022, ADR-0023): deleting a proxy IRI, against a real
 * {@code DatasetTx} over an in-memory RDF4J store.
 */
class ResourceVersioningTombstoneTest {

  private static final IRI CONTENT = new SimpleIRI("https://example.org/graph/content");
  private static final IRI A = new SimpleIRI("https://example.org/res/A");
  private static final IRI B = new SimpleIRI("https://example.org/res/B");
  private static final IRI A_PART = new SimpleIRI("https://example.org/res/A#part");
  private static final IRI P = new SimpleIRI("https://example.org/vocab/p");
  private static final IRI Q = new SimpleIRI("https://example.org/vocab/q");
  private static final IRI ALICE = new SimpleIRI("https://example.org/people/alice");
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

  @Test
  void deletingAPublishedProxyIriRemovesDraftAndPublishedSliceAndKeepsTheVersion() {
    final Graph draft = one(A, "one");
    draft.add(A_PART, P, rdf.createLiteral("part"));
    draft(A, draft);
    draft(B, one(B, "b"));
    publish(A, B);
    final IRI cid = generator.generateIri(A, draft);

    final DeleteResult result = delete(A, PublishDetails.NONE);

    assertThat(draftOf(A).isEmpty()).isTrue();
    assertThat(draftOf(B).size()).isEqualTo(1);
    assertThat(transactor.inTransaction(tx -> versioning.readPublished(tx, A)).isEmpty()).isTrue();
    assertThat(transactor.inTransaction(tx -> versioning.readPublished(tx, B)).size()).isEqualTo(1);
    assertThat(version(cid)).isPresent();
    assertThat(version(A, 1)).isPresent();
    assertThat(result.entry().getIRIString()).startsWith("urn:uuid:");
  }

  @Test
  void theTombstoneIsAnEntryOfItsOwnNamingTheLastVersion() {
    draft(A, one(A, "one"));
    final IRI published = publish(A).entries().get(A);
    final IRI cid = generator.generateIri(A, one(A, "one"));

    final DeleteResult result = delete(A, PublishDetails.NONE.withCreator(ALICE).withComment("gone"));

    final List<HistoryEntry> history = historyOf(A);
    assertThat(history).hasSize(2);
    assertThat(history.get(0).iri()).isEqualTo(published);
    assertThat(history.get(0).kind()).isEqualTo(EntryKind.PUBLISH);
    assertThat(history.get(1)).isEqualTo(new HistoryEntry(result.entry(), 2, EntryKind.TOMBSTONE, Optional.of(cid),
        Optional.of("ni-rdf/1"), NOW, Optional.of(ALICE), Optional.of("gone")));
    assertThat(result.entry()).isNotEqualTo(published);
  }

  @Test
  void theTombstoneOfAProxyIriThatWasNeverPublishedNamesNoVersion() {
    draft(A, one(A, "one"));

    final DeleteResult result = delete(A, PublishDetails.NONE);

    assertThat(draftOf(A).isEmpty()).isTrue();
    assertThat(historyOf(A)).singleElement().satisfies(entry -> {
      assertThat(entry.iri()).isEqualTo(result.entry());
      assertThat(entry.sequence()).isEqualTo(1);
      assertThat(entry.kind()).isEqualTo(EntryKind.TOMBSTONE);
      assertThat(entry.content()).isEmpty();
      assertThat(entry.procedure()).isEmpty();
    });
  }

  @Test
  void aProxyIriThatWasNeverKnownGetsATombstone() {
    final DeleteResult result = delete(A, PublishDetails.NONE);

    assertThat(historyOf(A)).singleElement().extracting(HistoryEntry::iri).isEqualTo(result.entry());
    assertThat(state(A)).isEqualTo(PublishState.DELETED);
  }

  @Test
  void theTombstoneIsStoredAsATypedEntryAmongTheTriplesOfTheHistoryGraph() {
    draft(A, one(A, "one"));
    publish(A);

    final IRI entry = delete(A, PublishDetails.NONE).entry();

    final ReadableGraph history = transactor.inTransaction(tx -> tx.export(VersioningGraphs.HISTORY));
    assertThat(history.contains(rdf.createTriple(A, VocabVersioning.ENTRY, entry))).isTrue();
    assertThat(history.contains(rdf.createTriple(entry, VocabRdf.TYPE, VocabVersioning.TOMBSTONE))).isTrue();
    assertThat(
        history.contains(rdf.createTriple(entry, VocabVersioning.SEQUENCE, rdf.createLiteral("2", VocabXsd.INTEGER))))
        .isTrue();
    assertThat(history.stream(entry, VocabDct.CREATED, null).count()).isEqualTo(1);
  }

  @Test
  void deleteRaisesTheTokenAndTheNextWriteNeedsIt() {
    draft(A, one(A, "one"));
    final RevisionToken before = token(A);

    final DeleteResult result = delete(A, PublishDetails.NONE);

    assertThat(result.token().revision()).isEqualTo(before.revision() + 1);
    assertThat(token(A)).isEqualTo(result.token());
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.replaceSlice(tx, A, one(A, "again"), before)))
        .isInstanceOf(RevisionConflictException.class);
    transactor.inTransaction(tx -> versioning.replaceSlice(tx, A, one(A, "again"), result.token()));
  }

  @Test
  void aDeletedProxyIriReadsAsDeletedAndDeletingAgainIsANoOp() {
    draft(A, one(A, "one"));
    publish(A);
    final DeleteResult first = delete(A, PublishDetails.NONE);

    final DeleteResult second = transactor
        .inTransaction(tx -> versioning.delete(tx, A, first.token(), PublishDetails.NONE.withComment("again")));

    assertThat(state(A)).isEqualTo(PublishState.DELETED);
    assertThat(second.entry()).isEqualTo(first.entry());
    assertThat(second.token()).isEqualTo(first.token());
    assertThat(historyOf(A)).hasSize(2);
  }

  @Test
  void aDraftWrittenAfterTheDeleteIsDeletedAgainWithTheSameLastVersion() {
    draft(A, one(A, "one"));
    publish(A);
    final DeleteResult first = delete(A, PublishDetails.NONE);
    transactor.inTransaction(tx -> versioning.replaceSlice(tx, A, one(A, "later"), first.token()));

    final DeleteResult second = delete(A, PublishDetails.NONE);

    assertThat(second.entry()).isNotEqualTo(first.entry());
    final List<HistoryEntry> history = historyOf(A);
    assertThat(history).extracting(HistoryEntry::sequence).containsExactly(1L, 2L, 3L);
    assertThat(history.get(2).content()).isEqualTo(history.get(0).content());
    assertThat(draftOf(A).isEmpty()).isTrue();
  }

  @Test
  void publishingTheSameContentAfterADeleteCreatesANewEntryAndRestoresThePublishedState() {
    draft(A, one(A, "one"));
    publish(A);
    delete(A, PublishDetails.NONE);
    draft(A, one(A, "one"));

    final IRI entry = publish(A).entries().get(A);

    final List<HistoryEntry> history = historyOf(A);
    assertThat(history).extracting(HistoryEntry::kind)
        .containsExactly(EntryKind.PUBLISH, EntryKind.TOMBSTONE, EntryKind.PUBLISH);
    assertThat(history.get(2).iri()).isEqualTo(entry);
    assertThat(history.get(2).content()).isEqualTo(history.get(0).content());
    assertThat(state(A)).isEqualTo(PublishState.PUBLISHED_UNCHANGED);
    assertThat(transactor.inTransaction(tx -> versioning.readPublished(tx, A)).size()).isEqualTo(1);
  }

  @Test
  void aDraftWrittenAfterTheDeleteDoesNotChangeTheDeletedState() {
    draft(A, one(A, "one"));
    publish(A);
    delete(A, PublishDetails.NONE);
    draft(A, one(A, "later"));

    assertThat(state(A)).isEqualTo(PublishState.DELETED);
  }

  @Test
  void theTombstoneIsNoVersion() {
    draft(A, one(A, "one"));
    publish(A);
    delete(A, PublishDetails.NONE);

    assertThat(version(A, 2)).isEmpty();
  }

  @Test
  void deleteRemovesNoVersionFromTheContentStore() {
    draft(A, one(A, "one"));
    draft(B, one(B, "b"));
    publish(A, B);
    final long before = countIn(VersioningGraphs.CONTENT_STORE);

    delete(A, PublishDetails.NONE);
    delete(B, PublishDetails.NONE);

    assertThat(before).isPositive();
    assertThat(countIn(VersioningGraphs.CONTENT_STORE)).isEqualTo(before);
  }

  @Test
  void aStaleTokenIsReportedAndChangesNothing() {
    draft(A, one(A, "one"));
    final RevisionToken stale = token(A);
    draft(A, one(A, "two"));

    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.delete(tx, A, stale, PublishDetails.NONE)))
        .isInstanceOf(RevisionConflictException.class);
    assertThat(draftOf(A).size()).isEqualTo(1);
    assertThat(historyOf(A)).isEmpty();
  }

  @Test
  void aTokenOfAnotherProxyIriIsRejected() {
    final RevisionToken other = token(B);

    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.delete(tx, A, other, PublishDetails.NONE)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aProxyIriWithAFragmentIsRejected() {
    final RevisionToken token = token(A);

    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.delete(tx, A_PART, token, PublishDetails.NONE)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankNodeOfTheDraftThatAnotherSliceUsesIsRejectedAndChangesNothing() {
    final BlankNode shared = rdf.createBlankNode("shared");
    final Graph existing = new SimpleGraph();
    existing.add(A, P, shared);
    existing.add(shared, Q, rdf.createLiteral("x"));
    existing.add(B, P, shared);
    transactor.inTransaction(tx -> tx.add(CONTENT, existing));
    final RevisionToken token = token(A);

    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.delete(tx, A, token, PublishDetails.NONE)))
        .isInstanceOf(AmbiguousBlankNodeException.class);
    assertThat(countIn(CONTENT)).isEqualTo(3);
    assertThat(historyOf(A)).isEmpty();
  }

  @Test
  void aDeleteOnAnotherContentGraphIsRejected() {
    draft(A, one(A, "one"));
    final RevisionToken token = token(A);
    final ResourceVersioning other = new ResourceVersioningNamedGraphs(
        new SimpleIRI("https://example.org/graph/other"));

    assertThatThrownBy(() -> transactor.inTransaction(tx -> other.delete(tx, A, token, PublishDetails.NONE)))
        .isInstanceOf(ContentGraphMismatchException.class);
  }

  @Test
  void anAbortedTransactionLeavesNoTombstoneBehind() {
    draft(A, one(A, "one"));
    publish(A);
    final RevisionToken token = token(A);

    assertThatThrownBy(() -> transactor.inTransaction(tx -> {
      versioning.delete(tx, A, token, PublishDetails.NONE);
      throw new IllegalStateException("abort");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(historyOf(A)).hasSize(1);
    assertThat(draftOf(A).size()).isEqualTo(1);
    assertThat(token(A)).isEqualTo(token);
  }

  @Test
  void aNullArgumentIsRejected() {
    final RevisionToken token = token(A);

    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.delete(tx, null, token, PublishDetails.NONE)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.delete(tx, A, null, PublishDetails.NONE)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.delete(tx, A, token, null)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> versioning.delete(null, A, token, PublishDetails.NONE))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aTombstoneWithAContentButNoProcedureIsAnIllegalState() {
    final IRI entryIri = new SimpleIRI("urn:uuid:00000000-0000-0000-0000-000000000001");
    final Graph entry = new SimpleGraph();
    entry.add(A, VocabVersioning.ENTRY, entryIri);
    entry.add(entryIri, VocabRdf.TYPE, VocabVersioning.TOMBSTONE);
    entry.add(entryIri, VocabVersioning.SEQUENCE, rdf.createLiteral("1", VocabXsd.INTEGER));
    entry.add(entryIri, VocabVersioning.CONTENT, new SimpleIRI("ni:///sha3-256;x"));
    entry.add(entryIri, VocabDct.CREATED, rdf.createLiteral(NOW.toString(), VocabXsd.DATETIME));
    transactor.inTransaction(tx -> tx.add(VersioningGraphs.HISTORY, entry));

    assertThatThrownBy(() -> historyOf(A)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("malformed history entry");
  }

  // --- helpers

  private DeleteResult delete(final IRI proxy, final PublishDetails details) {
    return transactor
        .inTransaction(tx -> versioning.delete(tx, proxy, versioning.readSlice(tx, proxy).token(), details));
  }

  private Optional<ReadableGraph> version(final IRI cid) {
    return transactor.inTransaction(tx -> versioning.readVersion(tx, cid));
  }

  private Optional<ReadableGraph> version(final IRI proxy, final long sequence) {
    return transactor.inTransaction(tx -> versioning.readVersion(tx, proxy, sequence));
  }

  private RevisionToken token(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.readSlice(tx, proxy).token());
  }

  private ReadableGraph draftOf(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.readSlice(tx, proxy).triples());
  }

  private List<HistoryEntry> historyOf(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.history(tx, proxy));
  }

  private PublishState state(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.publishState(tx, proxy));
  }

  private long countIn(final IRI graph) {
    return transactor.inTransaction(tx -> tx.export(graph).stream().count());
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
    return transactor.inTransaction(tx -> versioning.publish(tx,
        List.of(proxies).stream().map(proxy -> versioning.readSlice(tx, proxy).token()).toList(), PublishDetails.NONE));
  }
}
