// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.cid.ContentAddressedIriGeneratorSexpr;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.SimpleGraph;
import io.kogn.rdf.terms.SimpleIRI;
import io.kogn.rdf.terms.SimpleRdf;

/**
 * What the operations promise where the data or the transaction is not as they expect it: a transaction that has
 * ended, an RDF 1.2 triple term in the content graph (ADR-0022), and the resolution of the history timestamp
 * (ADR-0023).
 */
class ResourceVersioningBackendLimitsTest {

  private static final String EX = "https://example.org/";
  private static final IRI CONTENT = new SimpleIRI(EX + "graph/content");
  private static final IRI A = new SimpleIRI(EX + "res/A");
  private static final IRI P = new SimpleIRI(EX + "vocab/p");
  private static final String TRIPLE_TERM_UPDATE = "INSERT DATA { GRAPH <" + CONTENT.getIRIString() + "> { <"
      + A.getIRIString() + "> <" + P.getIRIString() + "> <<( <" + EX + "a> <" + EX + "b> <" + EX + "c> )>> } }";
  private static final Instant WITH_NANOS = Instant.parse("2026-10-31T09:00:00.123456789Z");
  private static final Instant TRUNCATED = Instant.parse("2026-10-31T09:00:00.123Z");

  private final SimpleRdf rdf = new SimpleRdf();
  private Repository repository;
  private DatasetTransactor transactor;

  @BeforeEach
  void setUp() {
    repository = new SailRepository(new MemoryStore());
    repository.init();
    transactor = new DatasetTransactorRdf4j(repository);
  }

  @AfterEach
  void tearDown() {
    repository.shutDown();
  }

  @Test
  void everyOperationOnATransactionThatHasEndedIsAnIllegalState() {
    final ResourceVersioning versioning = new ResourceVersioningNamedGraphs(CONTENT);
    final DatasetTx ended = transactor.inTransaction(tx -> tx);
    final RevisionToken token = new RevisionToken(A, 0);
    final SimpleGraph content = new SimpleGraph();
    content.add(A, P, rdf.createLiteral("one"));

    assertThatThrownBy(() -> versioning.readSlice(ended, A)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> versioning.replaceSlice(ended, A, content, token))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> versioning.delete(ended, A, token, PublishDetails.NONE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aTripleTermInTheSliceIsAnIllegalStateAndLeavesTheDatasetAlone() {
    final ResourceVersioning versioning = new ResourceVersioningNamedGraphs(CONTENT);
    transactor.inTransaction(tx -> {
      tx.update(TRIPLE_TERM_UPDATE);
      return null;
    });
    final RevisionToken token = new RevisionToken(A, 0);

    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.readSlice(tx, A)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("triple term");
    assertThatThrownBy(
        () -> transactor.inTransaction(tx -> versioning.publish(tx, List.of(token), PublishDetails.NONE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("triple term");
    final boolean historyWritten = transactor.inTransaction(tx -> tx.contains(VersioningGraphs.HISTORY, A, null, null));
    assertThat(historyWritten).isFalse();
  }

  @Test
  void theTimestampOfAnEntryHasMillisecondResolution() {
    final ResourceVersioning versioning = new ResourceVersioningNamedGraphs(CONTENT,
        new ContentAddressedIriGeneratorSexpr(), Clock.fixed(WITH_NANOS, ZoneOffset.UTC));
    final SimpleGraph content = new SimpleGraph();
    content.add(A, P, rdf.createLiteral("one"));
    transactor.inTransaction(tx -> {
      final RevisionToken token = versioning.replaceSlice(tx, A, content, versioning.readSlice(tx, A).token());
      return versioning.publish(tx, List.of(token), PublishDetails.NONE);
    });

    final HistoryEntry entry = transactor.inTransaction(tx -> versioning.history(tx, A)).getFirst();

    assertThat(entry.created()).isEqualTo(TRUNCATED);
  }
}
