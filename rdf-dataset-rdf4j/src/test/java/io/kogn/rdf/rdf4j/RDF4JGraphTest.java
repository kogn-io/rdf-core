// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Triple;

/**
 * Unit tests for {@link RDF4JGraph}: a null subject fails with a named {@link NullPointerException}
 * (issue #85), and triples are matched regardless of the statement context an exported graph keeps
 * (issue #152 R1-1).
 */
class RDF4JGraphTest {

  private static final ValueFactory VF = SimpleValueFactory.getInstance();
  private static final IRI SUBJECT = RDF4JIRI.of("https://example.org/subject");
  private static final IRI GRAPH = RDF4JIRI.of("https://example.org/graph");
  private static final IRI PREDICATE = RDF4JIRI.of("https://example.org/predicate");
  private static final IRI OBJECT = RDF4JIRI.of("https://example.org/object");

  @Test
  @DisplayName("add with a null subject fails with a clear NullPointerException, not a bare NPE"
      + " out of Object#getClass on the unsupported-type fallback (issue #85)")
  void add_withNullSubject_throwsNullPointerExceptionWithMessage() {
    // given
    final RDF4JGraph graph = new RDF4JGraph();

    // when, then — the failure must name the violated precondition rather than surface as an
    // unqualified NullPointerException out of resource.getClass() on RDF4JConverters'
    // unsupported-type fallback.
    assertThatThrownBy(() -> graph.add(null, PREDICATE, OBJECT)).isInstanceOf(NullPointerException.class)
        .hasMessage("resource must not be null");
  }

  /** A graph as {@code export} returns it: the statement keeps its named-graph context. */
  private static RDF4JGraph graphWithContextualStatement() {
    final Model model = new LinkedHashModel();
    model.add(VF.createIRI(SUBJECT.getIRIString()), VF.createIRI(PREDICATE.getIRIString()),
        VF.createIRI(OBJECT.getIRIString()), VF.createIRI(GRAPH.getIRIString()));
    return new RDF4JGraph(model);
  }

  private static Triple triple() {
    return new RDF4JTriple(VF.createIRI(SUBJECT.getIRIString()), VF.createIRI(PREDICATE.getIRIString()),
        VF.createIRI(OBJECT.getIRIString()));
  }

  @Test
  @DisplayName("contains finds a triple whose statement carries a context (issue #152 R1-1)")
  void contains_ignoresStatementContext() {
    final RDF4JGraph graph = graphWithContextualStatement();

    assertThat(graph.contains(triple())).isTrue();
  }

  @Test
  @DisplayName("add of a triple already present under a context does not grow the graph (issue #152 R1-1)")
  void add_ofTripleAlreadyPresentUnderContext_doesNotDuplicate() {
    final RDF4JGraph graph = graphWithContextualStatement();

    graph.add(triple());
    graph.add(triple().getSubject(), triple().getPredicate(), triple().getObject());

    assertThat(graph.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("remove deletes a triple that carries a context (issue #152 R1-1)")
  void remove_ignoresStatementContext() {
    final RDF4JGraph graph = graphWithContextualStatement();

    graph.remove(triple());

    assertThat(graph.isEmpty()).isTrue();
  }
}
