// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

import io.kogn.rdf.dataset.ConcurrencyConflictException;
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

/**
 * What concurrent operations of a versioned dataset do to each other on the {@code SERIALIZABLE}
 * RDF4J backend (issue #226): operations on disjoint proxy IRIs observe disjoint patterns and both
 * commit; operations on the same proxy IRI, and two first copies of the same content, still
 * conflict.
 *
 * <p>Each case lets a slow transaction run its operation, commits a competing transaction in
 * between, and then lets the slow one commit, so the competitor's changes are committed while the
 * slow transaction still holds what it observed. Repeated, because a conflict that is missed only
 * sometimes would otherwise pass.</p>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
class ResourceVersioningConcurrencyTest {

  private static final int RUNS = 20;

  private static final IRI CONTENT = new SimpleIRI("https://example.org/graph/content");
  private static final IRI A = new SimpleIRI("https://example.org/res/A");
  private static final IRI B = new SimpleIRI("https://example.org/res/B");
  private static final IRI C = new SimpleIRI("https://example.org/res/C");
  private static final IRI D = new SimpleIRI("https://example.org/res/D");
  private static final IRI P = new SimpleIRI("https://example.org/vocab/p");
  private static final IRI Q = new SimpleIRI("https://example.org/vocab/q");

  private final SimpleRdf rdf = new SimpleRdf();
  private Repository repository;
  private DatasetTransactor transactor;
  private ResourceVersioning versioning;

  /**
   * A is published and unchanged; B is published and holds an unpublished draft, so a publish of B
   * writes a new version. Both slices carry a fragment subject and a blank node.
   */
  @BeforeEach
  void setUp() {
    repository = new SailRepository(new MemoryStore());
    repository.init();
    transactor = new DatasetTransactorRdf4j(repository);
    versioning = new ResourceVersioningNamedGraphs(CONTENT);
    draft(A, resource(A, "a1"));
    publish(A);
    draft(B, resource(B, "b1"));
    publish(B);
    draft(B, resource(B, "b2"));
  }

  @AfterEach
  void tearDown() {
    repository.shutDown();
  }

  @RepeatedTest(RUNS)
  void writersOfDisjointProxyIrisBothCommit() throws InterruptedException {
    final RevisionToken tokenOfA = token(A);
    final RevisionToken tokenOfB = token(B);

    final Throwable outcome = outcomeAgainst(tx -> versioning.replaceSlice(tx, A, resource(A, "a2"), tokenOfA),
        () -> transactor.inTransaction(tx -> versioning.replaceSlice(tx, B, resource(B, "b3"), tokenOfB)));

    assertThat(outcome).isNull();
    assertThat(literalOf(A)).isEqualTo("a2");
    assertThat(literalOf(B)).isEqualTo("b3");
  }

  @RepeatedTest(RUNS)
  void aDraftReadOfOneProxyIriDoesNotConflictWithAWriteOfAnother() throws InterruptedException {
    final RevisionToken tokenOfB = token(B);

    final Throwable outcome = outcomeAgainst(tx -> versioning.readSlice(tx, A),
        () -> transactor.inTransaction(tx -> versioning.replaceSlice(tx, B, resource(B, "b3"), tokenOfB)));

    assertThat(outcome).isNull();
  }

  @RepeatedTest(RUNS)
  void aPublishStateReadOfOneProxyIriDoesNotConflictWithAWriteOfAnother() throws InterruptedException {
    final RevisionToken tokenOfB = token(B);

    final Throwable outcome = outcomeAgainst(tx -> versioning.publishState(tx, A),
        () -> transactor.inTransaction(tx -> versioning.replaceSlice(tx, B, resource(B, "b3"), tokenOfB)));

    assertThat(outcome).isNull();
  }

  @RepeatedTest(RUNS)
  void aVersionReadByCidDoesNotConflictWithAPublishOfAnotherProxyIri() throws InterruptedException {
    final IRI cidOfA = transactor.inTransaction(tx -> versioning.history(tx, A).getLast().content().orElseThrow());

    final Throwable outcome = outcomeAgainst(tx -> versioning.readVersion(tx, cidOfA), () -> publish(B));

    assertThat(outcome).isNull();
  }

  @RepeatedTest(RUNS)
  void aHistoryReadOfOneProxyIriDoesNotConflictWithAPublishOfAnother() throws InterruptedException {
    final Throwable outcome = outcomeAgainst(tx -> versioning.history(tx, A), () -> publish(B));

    assertThat(outcome).isNull();
  }

  @RepeatedTest(RUNS)
  void publishesOfDisjointProxyIrisWithDifferentContentBothCommit() throws InterruptedException {
    draft(A, resource(A, "a2"));
    final RevisionToken tokenOfA = token(A);

    final Throwable outcome = outcomeAgainst(tx -> versioning.publish(tx, List.of(tokenOfA), PublishDetails.NONE),
        () -> publish(B));

    assertThat(outcome).isNull();
    assertThat(historyOf(A)).hasSize(2);
    assertThat(historyOf(B)).hasSize(2);
  }

  @RepeatedTest(RUNS)
  void twoFirstPublishesOfTheSameContentDoNotBothCommit() throws InterruptedException {
    final Graph same = new SimpleGraph();
    same.add(C, P, rdf.createLiteral("same"));
    draft(C, same);
    final Graph sameForD = new SimpleGraph();
    sameForD.add(D, P, rdf.createLiteral("same"));
    draft(D, sameForD);
    final RevisionToken tokenOfC = token(C);

    final Throwable outcome = outcomeAgainst(tx -> versioning.publish(tx, List.of(tokenOfC), PublishDetails.NONE),
        () -> publish(D));

    assertThat(outcome).isInstanceOf(ConcurrencyConflictException.class);
  }

  @RepeatedTest(RUNS)
  void twoFirstOperationsOnAnUnboundDatasetDoNotBothCommitEvenForTheSameContentGraph() throws InterruptedException {
    repository.shutDown();
    repository = new SailRepository(new MemoryStore());
    repository.init();
    transactor = new DatasetTransactorRdf4j(repository);

    final Throwable outcome = outcomeAgainst(tx -> versioning.readSlice(tx, A),
        () -> transactor.inTransaction(tx -> versioning.readSlice(tx, B)));

    assertThat(outcome).isInstanceOf(ConcurrencyConflictException.class);
  }

  @RepeatedTest(RUNS)
  void writersOfTheSameProxyIriDoNotBothCommit() throws InterruptedException {
    final RevisionToken tokenOfA = token(A);

    final Throwable outcome = outcomeAgainst(tx -> versioning.replaceSlice(tx, A, resource(A, "slow"), tokenOfA),
        () -> transactor.inTransaction(tx -> versioning.replaceSlice(tx, A, resource(A, "competitor"), tokenOfA)));

    assertThat(outcome).isInstanceOf(ConcurrencyConflictException.class);
  }

  /**
   * Runs {@code slow} in a transaction, commits {@code competitor} in a transaction of its own
   * before the slow one commits, and returns what the slow transaction ended with: {@code null}
   * for a commit.
   */
  private Throwable outcomeAgainst(final Consumer<DatasetTx> slow, final Runnable competitor)
      throws InterruptedException {
    final CountDownLatch slowHasRun = new CountDownLatch(1);
    final CountDownLatch competitorCommitted = new CountDownLatch(1);
    final AtomicReference<Throwable> competitorFailure = new AtomicReference<>();
    final Thread competing = new Thread(() -> {
      try {
        slowHasRun.await();
        competitor.run();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (final RuntimeException e) {
        competitorFailure.set(e);
      } finally {
        competitorCommitted.countDown();
      }
    });
    competing.start();

    Throwable outcome = null;
    try {
      transactor.inTransaction(tx -> {
        slow.accept(tx);
        slowHasRun.countDown();
        awaitCommitted(competitorCommitted);
        return null;
      });
    } catch (final RuntimeException e) {
      outcome = e;
    } finally {
      slowHasRun.countDown();
    }
    competing.join();
    assertThat(competitorFailure.get()).isNull();
    return outcome;
  }

  private void awaitCommitted(final CountDownLatch latch) {
    try {
      latch.await();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** A slice with a literal on the proxy IRI, a fragment subject and a blank node. */
  private Graph resource(final IRI proxy, final String value) {
    final IRI fragment = new SimpleIRI(proxy.getIRIString() + "#part");
    final BlankNode node = rdf.createBlankNode();
    final Graph graph = new SimpleGraph();
    graph.add(proxy, P, rdf.createLiteral(value));
    graph.add(proxy, Q, node);
    graph.add(node, P, rdf.createLiteral(value + "-deep"));
    graph.add(fragment, P, rdf.createLiteral(value + "-part"));
    return graph;
  }

  private String literalOf(final IRI proxy) {
    final ReadableGraph slice = transactor.inTransaction(tx -> versioning.readSlice(tx, proxy).triples());
    return slice.stream(proxy, P, null)
        .map(triple -> ((Literal) triple.getObject()).getLexicalForm())
        .findFirst()
        .orElseThrow();
  }

  private List<HistoryEntry> historyOf(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.history(tx, proxy));
  }

  private RevisionToken token(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.readSlice(tx, proxy).token());
  }

  private void draft(final IRI proxy, final ReadableGraph content) {
    transactor
        .inTransaction(tx -> versioning.replaceSlice(tx, proxy, content, versioning.readSlice(tx, proxy).token()));
  }

  private void publish(final IRI proxy) {
    transactor.inTransaction(
        tx -> versioning.publish(tx, List.of(versioning.readSlice(tx, proxy).token()), PublishDetails.NONE));
  }
}
