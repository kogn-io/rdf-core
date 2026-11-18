// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.shacl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import io.kogn.rdf.shacl.ShaclReport;
import io.kogn.rdf.shacl.ShaclResult;
import io.kogn.rdf.shacl.ShaclValidationException;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;

/**
 * The SHACL constructs RDF4J's engine drops without an error — repetition paths, {@code sh:xone}
 * and SHACL-SPARQL constraint components — must be rejected with a
 * {@link ShaclValidationException} instead of yielding a report that conforms for data the shapes
 * forbid. Every data graph here violates the shape it is checked against, so a conforming report
 * is the false green the adapter has to prevent.
 */
class ShaclValidationRdf4jUnsupportedConstructsTest {

  private static final String EX = "https://example.org/";
  private static final String SH = "http://www.w3.org/ns/shacl#";
  private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
  private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";
  /** More values than any focus node here has, so {@code sh:minCount} is always violated. */
  private static final String UNREACHABLE_MIN_COUNT = "99";

  private final RDF rdf = new SimpleRdf();
  private final ShaclValidationRdf4j validation = new ShaclValidationRdf4j();

  /** The three SHACL repetition path operators. */
  enum Repetition {
    ZERO_OR_MORE("zeroOrMorePath"), ONE_OR_MORE("oneOrMorePath"), ZERO_OR_ONE("zeroOrOnePath");

    private final String local;

    Repetition(final String local) {
      this.local = local;
    }
  }

  /** Where in a path expression the repetition sits. */
  enum PathPlacement {
    DIRECT, IN_SEQUENCE, IN_ALTERNATIVE, UNDER_INVERSE, UNDER_REPETITION
  }

  /** Where the property shape carrying the path sits. */
  enum ShapePlacement {
    TARGETED, IN_OR, IN_NOT, IN_AND, IN_NODE, IN_QUALIFIED_VALUE_SHAPE
  }

  /** The three properties by which a constraint component declares a SPARQL-based validator. */
  enum ValidatorKind {
    VALIDATOR("validator"), NODE_VALIDATOR("nodeValidator"), PROPERTY_VALIDATOR("propertyValidator");

    private final String local;

    ValidatorKind(final String local) {
      this.local = local;
    }
  }

  static Stream<Arguments> repetitionInEveryPathPlacement() {
    return Stream.of(Repetition.values())
        .flatMap(repetition -> Stream.of(PathPlacement.values()).map(placement -> Arguments.of(repetition, placement)));
  }

  static Stream<Arguments> repetitionInEveryShapePlacement() {
    return Stream.of(Repetition.values())
        .flatMap(
            repetition -> Stream.of(ShapePlacement.values()).map(placement -> Arguments.of(repetition, placement)));
  }

  static Stream<Arguments> validatorInEveryShapePlacement() {
    return Stream.of(ValidatorKind.values())
        .flatMap(kind -> Stream.of(ShapePlacement.values()).map(placement -> Arguments.of(kind, placement)));
  }

  @ParameterizedTest
  @MethodSource("repetitionInEveryPathPlacement")
  void repetitionPathAnywhereInAPathExpressionIsRejected(final Repetition repetition, final PathPlacement placement) {
    final Graph shapes = rdf.createGraph();
    final RDFTerm path = placePath(shapes, placement, pathNode(shapes, repetition.local, ex("knows")));
    final BlankNode property = propertyShapeRequiringManyValuesOf(shapes, path);
    placeShape(shapes, ShapePlacement.TARGETED, property);

    assertRejected(shapes, SH + repetition.local);
  }

  @ParameterizedTest
  @MethodSource("repetitionInEveryShapePlacement")
  void repetitionPathInANestedShapeIsRejected(final Repetition repetition, final ShapePlacement placement) {
    final Graph shapes = rdf.createGraph();
    final BlankNode property = propertyShapeRequiringManyValuesOf(shapes,
        pathNode(shapes, repetition.local, ex("knows")));
    placeShape(shapes, placement, property);

    assertRejected(shapes, SH + repetition.local);
  }

  @Test
  void repetitionPathThatNoShapeReachesIsRejectedToo() {
    final Graph shapes = personShapeRequiringName();
    final BlankNode stray = rdf.createBlankNode();
    shapes.add(stray, sh("path"), pathNode(shapes, Repetition.ONE_OR_MORE.local, ex("knows")));

    assertRejected(shapes, SH + Repetition.ONE_OR_MORE.local);
  }

  @ParameterizedTest
  @EnumSource(ShapePlacement.class)
  void xoneIsRejectedWhereverItSits(final ShapePlacement placement) {
    final Graph shapes = rdf.createGraph();
    final BlankNode xoneShape = rdf.createBlankNode();
    // both branches hold for a person without a name -> sh:xone is violated
    shapes.add(xoneShape, sh("xone"), list(shapes, nodeKindShape(shapes, "IRI"), nodeKindShape(shapes, "IRI")));
    placeShape(shapes, placement, xoneShape);

    assertRejected(shapes, SH + "xone");
  }

  @Test
  void xoneOnAPropertyShapeIsRejected() {
    final Graph shapes = rdf.createGraph();
    final BlankNode property = rdf.createBlankNode();
    shapes.add(property, sh("path"), ex("knows"));
    // no branch holds for the IRI value ex:alice -> sh:xone is violated
    shapes.add(property, sh("xone"),
        list(shapes, nodeKindShape(shapes, "Literal"), nodeKindShape(shapes, "BlankNode")));
    placeShape(shapes, ShapePlacement.TARGETED, property);

    assertRejected(shapes, SH + "xone");
  }

  @ParameterizedTest
  @MethodSource("validatorInEveryShapePlacement")
  void sparqlConstraintComponentIsRejectedWhereverItIsUsed(final ValidatorKind kind, final ShapePlacement placement) {
    final Graph shapes = rdf.createGraph();
    final IRI component = ex("AlwaysFailsComponent");
    final BlankNode parameter = rdf.createBlankNode();
    final BlankNode validator = rdf.createBlankNode();
    shapes.add(component, a(), sh("ConstraintComponent"));
    shapes.add(component, sh("parameter"), parameter);
    shapes.add(parameter, sh("path"), ex("alwaysFails"));
    shapes.add(component, sh(kind.local), validator);
    shapes.add(validator, a(), sh("SPARQLAskValidator"));
    shapes.add(validator, sh("ask"), rdf.createLiteral("ASK { FILTER (false) }"));
    final BlankNode user = rdf.createBlankNode();
    shapes.add(user, ex("alwaysFails"), rdf.createLiteral("true", rdf.createIRI(XSD_NS + "boolean")));
    placeShape(shapes, placement, user);

    assertRejected(shapes, SH + kind.local);
  }

  @Test
  void sparqlConstraintIsStillEvaluated() {
    final Graph shapes = rdf.createGraph();
    final BlankNode node = rdf.createBlankNode();
    final BlankNode constraint = rdf.createBlankNode();
    shapes.add(node, sh("sparql"), constraint);
    shapes.add(constraint, a(), sh("SPARQLConstraint"));
    shapes.add(constraint, sh("select"), rdf.createLiteral("SELECT $this WHERE { $this a <" + EX + "Person> }"));
    placeShape(shapes, ShapePlacement.TARGETED, node);

    final ShaclReport report = validation.validate(person(), shapes, ValidationOptions.defaults());

    assertThat(report.conforms()).isFalse();
    assertThat(report.results()).extracting(ShaclResult::focusNode).containsExactly(ex("bob"));
  }

  @Test
  void orWithoutXoneOrRepetitionIsStillEvaluated() {
    final Graph shapes = rdf.createGraph();
    final BlankNode property = propertyShapeRequiringManyValuesOf(shapes, ex("knows"));
    placeShape(shapes, ShapePlacement.IN_OR, property);

    final ShaclReport report = validation.validate(person(), shapes, ValidationOptions.defaults());

    assertThat(report.conforms()).isFalse();
  }

  private void assertRejected(final Graph shapes, final String construct) {
    assertThatThrownBy(() -> validation.validate(person(), shapes, ValidationOptions.defaults()))
        .isInstanceOf(ShaclValidationException.class)
        .hasMessageContaining(construct);
  }

  /** A person who knows one other person and has no name. */
  private Graph person() {
    final Graph data = rdf.createGraph();
    data.add(ex("bob"), a(), ex("Person"));
    data.add(ex("bob"), ex("knows"), ex("alice"));
    return data;
  }

  private Graph personShapeRequiringName() {
    final Graph shapes = rdf.createGraph();
    final BlankNode property = propertyShapeRequiringManyValuesOf(shapes, ex("name"));
    placeShape(shapes, ShapePlacement.TARGETED, property);
    return shapes;
  }

  private BlankNode propertyShapeRequiringManyValuesOf(final Graph shapes, final RDFTerm path) {
    final BlankNode property = rdf.createBlankNode();
    shapes.add(property, sh("path"), path);
    shapes.add(property, sh("minCount"), rdf.createLiteral(UNREACHABLE_MIN_COUNT, rdf.createIRI(XSD_NS + "integer")));
    return property;
  }

  private BlankNode nodeKindShape(final Graph shapes, final String nodeKind) {
    final BlankNode shape = rdf.createBlankNode();
    shapes.add(shape, sh("nodeKind"), sh(nodeKind));
    return shape;
  }

  private RDFTerm placePath(final Graph shapes, final PathPlacement placement, final BlankNode repetition) {
    return switch (placement) {
    case DIRECT -> repetition;
    case IN_SEQUENCE -> list(shapes, ex("knows"), repetition);
    case IN_ALTERNATIVE -> pathNode(shapes, "alternativePath", list(shapes, ex("name"), repetition));
    case UNDER_INVERSE -> pathNode(shapes, "inversePath", repetition);
    case UNDER_REPETITION -> pathNode(shapes, Repetition.ZERO_OR_ONE.local, repetition);
    };
  }

  /**
   * Makes {@code shape} apply to {@code ex:Person}: targeted itself, or nested one level down in
   * a logical, node or qualified-value-shape constraint of a node shape targeting
   * {@code ex:Person}.
   */
  private void placeShape(final Graph shapes, final ShapePlacement placement, final BlankNode shape) {
    if (placement == ShapePlacement.TARGETED) {
      shapes.add(shape, sh("targetClass"), ex("Person"));
      return;
    }
    final IRI personShape = ex("PersonShape");
    shapes.add(personShape, a(), sh("NodeShape"));
    shapes.add(personShape, sh("targetClass"), ex("Person"));
    final RDFTerm nested = asNodeShape(shapes, shape);
    switch (placement) {
    case IN_OR -> shapes.add(personShape, sh("or"), list(shapes, nested, nodeKindShape(shapes, "Literal")));
    case IN_AND -> shapes.add(personShape, sh("and"), list(shapes, nested));
    case IN_NOT -> shapes.add(personShape, sh("not"), nested);
    case IN_NODE -> shapes.add(personShape, sh("node"), nested);
    case IN_QUALIFIED_VALUE_SHAPE -> {
      final BlankNode qualified = rdf.createBlankNode();
      shapes.add(qualified, sh("path"), ex("knows"));
      shapes.add(qualified, sh("qualifiedValueShape"), nested);
      shapes.add(qualified, sh("qualifiedMinCount"), rdf.createLiteral("1", rdf.createIRI(XSD_NS + "integer")));
      shapes.add(personShape, sh("property"), qualified);
    }
    case TARGETED -> throw new IllegalStateException("handled above");
    }
  }

  /** A property shape nested in another constraint is reached through a node shape. */
  private RDFTerm asNodeShape(final Graph shapes, final BlankNode shape) {
    if (shapes.stream(shape, sh("path"), null).findAny().isEmpty()) {
      return shape;
    }
    final BlankNode node = rdf.createBlankNode();
    shapes.add(node, sh("property"), shape);
    return node;
  }

  private BlankNode pathNode(final Graph shapes, final String operator, final RDFTerm operand) {
    final BlankNode node = rdf.createBlankNode();
    shapes.add(node, sh(operator), operand);
    return node;
  }

  private BlankNode list(final Graph shapes, final RDFTerm... items) {
    RDFTerm rest = rdf.createIRI(RDF_NS + "nil");
    for (int i = items.length - 1; i >= 0; i--) {
      final BlankNode cell = rdf.createBlankNode();
      shapes.add(cell, rdf.createIRI(RDF_NS + "first"), items[i]);
      shapes.add(cell, rdf.createIRI(RDF_NS + "rest"), rest);
      rest = cell;
    }
    return (BlankNode) rest;
  }

  private IRI ex(final String local) {
    return rdf.createIRI(EX + local);
  }

  private IRI sh(final String local) {
    return rdf.createIRI(SH + local);
  }

  private IRI a() {
    return rdf.createIRI(RDF_NS + "type");
  }
}
