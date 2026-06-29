package io.kogn.rdf.terms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SimpleGraphTest {

  private final RDF rdf = new SimpleRdf();

  private final IRI alice = rdf.createIRI("http://example.org/alice");
  private final IRI knows = rdf.createIRI("http://example.org/knows");
  private final IRI bob = rdf.createIRI("http://example.org/bob");

  @Test
  void newGraphIsEmpty() {
    Graph graph = rdf.createGraph();

    assertThat(graph.isEmpty()).isTrue();
    assertThat(graph.size()).isZero();
    assertThat(graph.stream()).isEmpty();
  }

  @Test
  void addStoresTriple() {
    Graph graph = rdf.createGraph();

    graph.add(alice, knows, bob);

    assertThat(graph.size()).isEqualTo(1);
    assertThat(graph.isEmpty()).isFalse();
    assertThat(graph.contains(rdf.createTriple(alice, knows, bob))).isTrue();
  }

  @Test
  void duplicateTriplesAreDeduplicated() {
    Graph graph = rdf.createGraph();

    graph.add(alice, knows, bob);
    graph.add(rdf.createTriple(alice, knows, bob));

    assertThat(graph.size()).isEqualTo(1);
  }

  @Test
  void removeDeletesTriple() {
    Graph graph = rdf.createGraph();
    graph.add(alice, knows, bob);

    graph.remove(rdf.createTriple(alice, knows, bob));

    assertThat(graph.isEmpty()).isTrue();
  }

  @Test
  void clearRemovesEverything() {
    Graph graph = rdf.createGraph();
    graph.add(alice, knows, bob);
    graph.add(bob, knows, alice);

    graph.clear();

    assertThat(graph.size()).isZero();
  }

  @Test
  void streamMatchesPatternWithWildcards() {
    Graph graph = rdf.createGraph();
    graph.add(alice, knows, bob);
    graph.add(bob, knows, alice);

    assertThat(graph.stream(alice, null, null)).hasSize(1);
    assertThat(graph.stream(null, knows, null)).hasSize(2);
    assertThat(graph.stream(null, null, alice)).hasSize(1);
    assertThat(graph.stream(alice, knows, bob)).hasSize(1);
    assertThat(graph.stream(alice, knows, alice)).isEmpty();
  }
}
