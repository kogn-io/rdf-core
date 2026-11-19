// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.versioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.SimpleGraph;
import io.kogn.rdf.terms.SimpleIRI;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabXsd;

/**
 * Terms the data model accepts but the RDF4J backend rejects (issue 246). The caller catches the
 * rejection inside the transaction and commits: the operation must not have written a part of
 * its change by then.
 */
class ResourceVersioningBackendRejectionTest {

  private static final IRI CONTENT = new SimpleIRI("https://example.org/graph/content");
  private static final IRI A = new SimpleIRI("https://example.org/res/A");
  private static final IRI B = new SimpleIRI("https://example.org/res/B");
  private static final IRI A_DOUBLE_FRAGMENT = new SimpleIRI("https://example.org/res/A#a#b");
  private static final IRI WITH_SPACE = new SimpleIRI("https://example.org/a b");
  private static final IRI P = new SimpleIRI("https://example.org/vocab/p");
  private static final IRI Q = new SimpleIRI("https://example.org/vocab/q");
  private static final String INVALID_LANGUAGE_TAG = "not a tag";

  private final SimpleRdf rdf = new SimpleRdf();
  private Repository repository;
  private DatasetTransactor transactor;
  private ResourceVersioning versioning;

  @BeforeEach
  void setUp() {
    repository = new SailRepository(new MemoryStore());
    repository.init();
    transactor = new DatasetTransactorRdf4j(repository);
    versioning = new ResourceVersioningNamedGraphs(CONTENT);
  }

  @AfterEach
  void tearDown() {
    repository.shutDown();
  }

  @Test
  void aReplacementWithAnIriTheBackendRejectsLeavesTheDraftAndTokenAlone() {
    assertRejectedReplacementChangesNothing(rdf.createTriple(A, Q, WITH_SPACE));
  }

  @Test
  void aReplacementWithAnIllTypedLiteralLeavesTheDraftAndTokenAlone() {
    assertRejectedReplacementChangesNothing(rdf.createTriple(A, Q, rdf.createLiteral("abc", VocabXsd.INTEGER)));
  }

  @Test
  void aReplacementWithAnInvalidLanguageTagLeavesTheDraftAndTokenAlone() {
    assertRejectedReplacementChangesNothing(rdf.createTriple(A, Q, rdf.createLiteral("x", INVALID_LANGUAGE_TAG)));
  }

  @Test
  void aReplacementWithASecondHashInAFragmentLeavesTheDraftAndTokenAlone() {
    assertRejectedReplacementChangesNothing(rdf.createTriple(A_DOUBLE_FRAGMENT, P, rdf.createLiteral("x")));
  }

  @Test
  void aReadOfAProxyIriTheBackendRejectsIsAnIllegalArgument() {
    assertThatThrownBy(() -> transactor.inTransaction(tx -> versioning.readSlice(tx, WITH_SPACE)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPublishWithACreatorTheBackendRejectsWritesNothing() {
    final RevisionToken a = draft(A, "one");
    final RevisionToken b = draft(B, "two");
    final Set<String> before = snapshot();

    final RuntimeException caught = caughtAndCommitted(
        tx -> versioning.publish(tx, List.of(a, b), PublishDetails.NONE.withCreator(WITH_SPACE)));

    assertThat(caught).isInstanceOf(IllegalArgumentException.class);
    assertThat(snapshot()).isEqualTo(before);
    assertThat(state(A)).isEqualTo(PublishState.NEVER_PUBLISHED);
  }

  @Test
  void aDeleteWithACreatorTheBackendRejectsWritesNothing() {
    draft(A, "one");
    transactor
        .inTransaction(tx -> versioning.publish(tx, List.of(versioning.readSlice(tx, A).token()), PublishDetails.NONE));
    final RevisionToken token = transactor.inTransaction(tx -> versioning.readSlice(tx, A).token());
    final Set<String> before = snapshot();

    final RuntimeException caught = caughtAndCommitted(
        tx -> versioning.delete(tx, A, token, PublishDetails.NONE.withCreator(WITH_SPACE)));

    assertThat(caught).isInstanceOf(IllegalArgumentException.class);
    assertThat(snapshot()).isEqualTo(before);
    assertThat(state(A)).isEqualTo(PublishState.PUBLISHED_UNCHANGED);
  }

  private void assertRejectedReplacementChangesNothing(final Triple rejected) {
    final RevisionToken token = draft(A, "old");
    final Set<String> before = snapshot();
    final Graph content = new SimpleGraph();
    content.add(A, P, rdf.createLiteral("new"));
    content.add(rejected);

    final RuntimeException caught = caughtAndCommitted(tx -> versioning.replaceSlice(tx, A, content, token));

    assertThat(caught).isInstanceOf(IllegalArgumentException.class);
    assertThat(snapshot()).isEqualTo(before);
    final DraftSlice slice = transactor.inTransaction(tx -> versioning.readSlice(tx, A));
    assertThat(slice.token()).isEqualTo(token);
    assertThat(slice.triples().contains(rdf.createTriple(A, P, rdf.createLiteral("old")))).isTrue();
  }

  /** Runs {@code operation}, catches its failure inside the transaction and commits anyway. */
  private RuntimeException caughtAndCommitted(final Function<DatasetTx, ?> operation) {
    final AtomicReference<RuntimeException> caught = new AtomicReference<>();
    transactor.inTransaction(tx -> {
      try {
        operation.apply(tx);
      } catch (final RuntimeException e) {
        caught.set(e);
      }
      return null;
    });
    assertThat(caught.get()).as("the operation must fail").isNotNull();
    return caught.get();
  }

  private PublishState state(final IRI proxy) {
    return transactor.inTransaction(tx -> versioning.publishState(tx, proxy));
  }

  private RevisionToken draft(final IRI proxy, final String value) {
    final Graph content = new SimpleGraph();
    content.add(proxy, P, rdf.createLiteral(value));
    return transactor
        .inTransaction(tx -> versioning.replaceSlice(tx, proxy, content, versioning.readSlice(tx, proxy).token()));
  }

  /** Every triple of the dataset's graphs, each prefixed by the graph it sits in. */
  private Set<String> snapshot() {
    return transactor.inTransaction(tx -> List
        .of(CONTENT, VersioningGraphs.CONTENT_STORE, VersioningGraphs.PUBLISHED, VersioningGraphs.HISTORY,
            VersioningGraphs.CONFIG)
        .stream()
        .flatMap(graph -> tx.snapshot(graph)
            .stream()
            .map(t -> graph.ntriplesString() + " " + t.getSubject().ntriplesString() + " "
                + t.getPredicate().ntriplesString() + " " + t.getObject().ntriplesString()))
        .collect(Collectors.toSet()));
  }
}
