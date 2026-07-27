// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.shacl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.sail.shacl.ast.ShaclUnsupportedException;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.shacl.Severity;
import io.kogn.rdf.shacl.ShaclMessage;
import io.kogn.rdf.shacl.ShaclReport;
import io.kogn.rdf.shacl.ShaclResult;
import io.kogn.rdf.shacl.ShaclValidationException;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;

/**
 * Acceptance tests for {@link ShaclValidationRdf4j}: conforms/violation reporting,
 * severity handling ({@code sh:Violation} vs {@code sh:Warning}) and RDFS subclass
 * reasoning, built purely on {@code rdf-terms} ({@link SimpleRdf}) — no
 * {@code rdf-dataset-rdf4j} types are used, matching the store-independence of this
 * module.
 */
class ShaclValidationRdf4jTest {

  private static final String EX = "https://example.org/";
  private static final String SH = "http://www.w3.org/ns/shacl#";
  private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
  private static final String RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#";
  private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";
  private static final String RDF4J_SHACL_EXTENSIONS_NS = "http://rdf4j.org/shacl-extensions#";

  private final RDF rdf = new SimpleRdf();
  private final ShaclValidationRdf4j validation = new ShaclValidationRdf4j();

  private IRI ex(String local) {
    return rdf.createIRI(EX + local);
  }

  private IRI sh(String local) {
    return rdf.createIRI(SH + local);
  }

  private IRI a() {
    return rdf.createIRI(RDF_NS + "type");
  }

  private IRI subClassOf() {
    return rdf.createIRI(RDFS_NS + "subClassOf");
  }

  private IRI xsdInteger() {
    return rdf.createIRI(XSD_NS + "integer");
  }

  private IRI xsdBoolean() {
    return rdf.createIRI(XSD_NS + "boolean");
  }

  private IRI rdf4jShaclExtension(String local) {
    return rdf.createIRI(RDF4J_SHACL_EXTENSIONS_NS + local);
  }

  @Test
  void conformingDataProducesNoResults() {
    Graph shapes = personShapeRequiringName();

    Graph data = rdf.createGraph();
    data.add(ex("alice"), a(), ex("Person"));
    data.add(ex("alice"), ex("name"), rdf.createLiteral("Alice"));

    ShaclReport report = validation.validate(data, shapes, ValidationOptions.defaults());

    assertThat(report.conforms()).isTrue();
    assertThat(report.results()).isEmpty();
  }

  @Test
  void violationYieldsNonConformingReportWithOneResult() {
    Graph shapes = personShapeRequiringName(rdf.createLiteral("Name is required"));

    Graph data = rdf.createGraph();
    data.add(ex("bob"), a(), ex("Person"));
    // no ex:name -> violates sh:minCount 1

    ShaclReport report = validation.validate(data, shapes, ValidationOptions.defaults());

    assertThat(report.conforms()).isFalse();
    assertThat(report.results()).hasSize(1);
    ShaclResult result = report.results().get(0);
    assertThat(result.focusNode()).isEqualTo(ex("bob").getIRIString());
    assertThat(result.path()).isEqualTo(ex("name").getIRIString());
    assertThat(result.severity()).isEqualTo(Severity.VIOLATION);
    assertThat(result.messages()).containsExactly(ShaclMessage.untagged("Name is required"));
  }

  @Test
  void warningOnlyResultsKeepReportConforming() {
    Graph shapes = rdf.createGraph();
    IRI personShape = ex("PersonShape");
    BlankNode emailProperty = rdf.createBlankNode();
    shapes.add(personShape, a(), sh("NodeShape"));
    shapes.add(personShape, sh("targetClass"), ex("Person"));
    shapes.add(personShape, sh("property"), emailProperty);
    shapes.add(emailProperty, sh("path"), ex("email"));
    shapes.add(emailProperty, sh("minCount"), rdf.createLiteral("1", xsdInteger()));
    shapes.add(emailProperty, sh("severity"), sh("Warning"));
    shapes.add(emailProperty, sh("message"), rdf.createLiteral("Email is recommended"));

    Graph data = rdf.createGraph();
    data.add(ex("carol"), a(), ex("Person"));
    // no ex:email -> violates sh:minCount 1, but at sh:Warning severity

    ShaclReport report = validation.validate(data, shapes, ValidationOptions.defaults());

    assertThat(report.conforms()).isTrue();
    assertThat(report.results()).hasSize(1);
    ShaclResult result = report.results().get(0);
    assertThat(result.severity()).isEqualTo(Severity.WARNING);
    assertThat(result.messages()).containsExactly(ShaclMessage.untagged("Email is recommended"));
  }

  @Test
  void rdfsSubClassReasoningDisabledDoesNotFireOnSubclassInstances() {
    Graph shapes = animalShapeRequiringName();

    Graph data = rdf.createGraph();
    data.add(ex("Dog"), subClassOf(), ex("Animal"));
    data.add(ex("rex"), a(), ex("Dog"));
    // no ex:name -> would violate sh:minCount 1 *if* the shape fired

    ShaclReport report = validation.validate(data, shapes, new ValidationOptions(false));

    assertThat(report.conforms()).isTrue();
    assertThat(report.results()).isEmpty();
  }

  @Test
  void rdfsSubClassReasoningEnabledFiresOnSubclassInstances() {
    Graph shapes = animalShapeRequiringName();

    Graph data = rdf.createGraph();
    data.add(ex("Dog"), subClassOf(), ex("Animal"));
    data.add(ex("rex"), a(), ex("Dog"));
    // no ex:name -> violates sh:minCount 1 once the shape fires via subclass reasoning

    ShaclReport report = validation.validate(data, shapes, new ValidationOptions(true));

    assertThat(report.conforms()).isFalse();
    assertThat(report.results()).hasSize(1);
    assertThat(report.results().get(0).focusNode()).isEqualTo(ex("rex").getIRIString());
    assertThat(report.results().get(0).severity()).isEqualTo(Severity.VIOLATION);
  }

  /**
   * Pins where the {@code rdfs:subClassOf} axioms may live: this backend picks them up from the
   * <em>shapes</em> graph too, not only from the data graph (as
   * {@link #rdfsSubClassReasoningEnabledFiresOnSubclassInstances()} covers). Both placements
   * work, so a consumer need not merge ontology axioms into its candidate data.
   */
  @Test
  void rdfsSubClassReasoningAlsoFiresWhenAxiomsLiveInTheShapesGraph() {
    Graph shapes = animalShapeRequiringName();
    shapes.add(ex("Dog"), subClassOf(), ex("Animal"));

    Graph data = rdf.createGraph();
    data.add(ex("rex"), a(), ex("Dog"));
    // no ex:name -> violates sh:minCount 1 once the shape fires via subclass reasoning

    ShaclReport report = validation.validate(data, shapes, new ValidationOptions(true));

    assertThat(report.conforms()).isFalse();
    assertThat(report.results()).hasSize(1);
    assertThat(report.results().get(0).focusNode()).isEqualTo(ex("rex").getIRIString());
  }

  /**
   * Pins the actual trap: the flag reasons over axioms that are present, it does not invent
   * them. With no {@code rdfs:subClassOf} axiom in either input graph, enabling the option is a
   * silent no-op — the shape never fires and validation reports success.
   */
  @Test
  void rdfsSubClassReasoningWithoutAnyAxiomIsASilentNoOp() {
    Graph shapes = animalShapeRequiringName();

    Graph data = rdf.createGraph();
    data.add(ex("rex"), a(), ex("Dog"));

    ShaclReport report = validation.validate(data, shapes, new ValidationOptions(true));

    assertThat(report.conforms()).isTrue();
    assertThat(report.results()).isEmpty();
  }

  /**
   * Pins issue #67: {@link ValidationOptions#rdfsSubClassReasoning()} is the sole authority
   * over subclass reasoning. A shapes graph carrying RDF4J's proprietary
   * {@code http://rdf4j.org/shacl-extensions#rdfsSubClassReasoning} override must not flip a
   * caller's explicit {@code false} to {@code true} — the port would otherwise let the shapes
   * graph override the caller, which a second SHACL engine that does not know this predicate
   * would not honor.
   */
  @Test
  void rdf4jExtensionPredicateOnAShapeDoesNotOverrideADisabledCallerOption() {
    Graph shapes = animalShapeRequiringName();
    shapes.add(ex("AnimalShape"), rdf4jShaclExtension("rdfsSubClassReasoning"),
        rdf.createLiteral("true", xsdBoolean()));

    Graph data = rdf.createGraph();
    data.add(ex("Dog"), subClassOf(), ex("Animal"));
    data.add(ex("rex"), a(), ex("Dog"));
    // no ex:name -> would violate sh:minCount 1 if the shape fired

    ShaclReport report = validation.validate(data, shapes, ValidationOptions.defaults());

    assertThat(report.conforms()).isTrue();
    assertThat(report.results()).isEmpty();
  }

  /**
   * Counter-proof for {@link #rdf4jExtensionPredicateOnAShapeDoesNotOverrideADisabledCallerOption()}:
   * the very same shapes (extension predicate included) and data do violate once the caller
   * itself enables {@link ValidationOptions#rdfsSubClassReasoning()}. This shows the shape is
   * capable of firing and that the previous test's conforming report is not conforming for some
   * unrelated reason.
   */
  @Test
  void sameShapesAndDataViolateWhenTheCallerEnablesSubClassReasoning() {
    Graph shapes = animalShapeRequiringName();
    shapes.add(ex("AnimalShape"), rdf4jShaclExtension("rdfsSubClassReasoning"),
        rdf.createLiteral("true", xsdBoolean()));

    Graph data = rdf.createGraph();
    data.add(ex("Dog"), subClassOf(), ex("Animal"));
    data.add(ex("rex"), a(), ex("Dog"));
    // no ex:name -> violates sh:minCount 1 once the shape fires via subclass reasoning

    ShaclReport report = validation.validate(data, shapes, new ValidationOptions(true));

    assertThat(report.conforms()).isFalse();
    assertThat(report.results()).hasSize(1);
    assertThat(report.results().get(0).focusNode()).isEqualTo(ex("rex").getIRIString());
    assertThat(report.results().get(0).severity()).isEqualTo(Severity.VIOLATION);
  }

  /**
   * Pins the second half of switching RDF4J's extensions off: the extension vocabulary is
   * inert as a whole, not only where it overrides {@link ValidationOptions}. A shape whose
   * <em>only</em> target is {@code rdf4j-ext:targetShape} therefore has no target at all and
   * never fires — and the failure mode is a silently conforming report, not an error. Pinned
   * so the trade-off stays a decision rather than an accident.
   */
  @Test
  void aShapeTargetingOnlyThroughTheRdf4jTargetShapeExtensionNeverFires() {
    Graph shapes = animalShapeRequiringNameWithoutATarget();
    shapes.add(ex("AnimalShape"), rdf4jShaclExtension("targetShape"), ex("AnimalTargetShape"));
    shapes.add(ex("AnimalTargetShape"), a(), sh("PropertyShape"));
    shapes.add(ex("AnimalTargetShape"), sh("path"), a());
    shapes.add(ex("AnimalTargetShape"), sh("hasValue"), ex("Animal"));

    Graph data = rdf.createGraph();
    data.add(ex("rex"), a(), ex("Animal"));
    // no ex:name -> would violate sh:minCount 1 if the extension target selected ex:rex

    ShaclReport report = validation.validate(data, shapes, ValidationOptions.defaults());

    assertThat(report.conforms()).isTrue();
    assertThat(report.results()).isEmpty();
  }

  /**
   * Counter-proof for {@link #aShapeTargetingOnlyThroughTheRdf4jTargetShapeExtensionNeverFires()}:
   * the same shape and data do violate once the shape carries a standard SHACL target. This
   * shows the constraint is capable of firing and that the previous test's conforming report
   * comes from the dead extension target, not from a broken constraint or from data that
   * happens to satisfy it.
   */
  @Test
  void theSameShapeFiresOnceItCarriesAStandardShaclTarget() {
    Graph shapes = animalShapeRequiringNameWithoutATarget();
    shapes.add(ex("AnimalShape"), rdf4jShaclExtension("targetShape"), ex("AnimalTargetShape"));
    shapes.add(ex("AnimalTargetShape"), a(), sh("PropertyShape"));
    shapes.add(ex("AnimalTargetShape"), sh("path"), a());
    shapes.add(ex("AnimalTargetShape"), sh("hasValue"), ex("Animal"));
    shapes.add(ex("AnimalShape"), sh("targetClass"), ex("Animal"));

    Graph data = rdf.createGraph();
    data.add(ex("rex"), a(), ex("Animal"));
    // no ex:name -> violates sh:minCount 1 via the standard sh:targetClass

    ShaclReport report = validation.validate(data, shapes, ValidationOptions.defaults());

    assertThat(report.conforms()).isFalse();
    assertThat(report.results()).hasSize(1);
    assertThat(report.results().get(0).focusNode()).isEqualTo(ex("rex").getIRIString());
    assertThat(report.results().get(0).severity()).isEqualTo(Severity.VIOLATION);
  }

  /**
   * Pins the load-bearing fix of issue #20: a shape carrying one {@code sh:message} per
   * language must surface <em>all</em> of them, tags intact. Reducing them to one string
   * made bilingual shapes impossible — the survivor was decided by the parse order of the
   * shapes graph, and its tag was dropped, so a caller could not even tell which language
   * it had been handed.
   */
  @Test
  void allMessagesSurviveWithTheirLanguageTags() {
    Graph shapes = personShapeRequiringName(rdf.createLiteral("Name fehlt.", "de"),
        rdf.createLiteral("Name is required.", "en"));

    Graph data = rdf.createGraph();
    data.add(ex("bob"), a(), ex("Person"));

    ShaclReport report = validation.validate(data, shapes, ValidationOptions.defaults());

    assertThat(report.results()).hasSize(1);
    assertThat(report.results().get(0).messages()).containsExactlyInAnyOrder(new ShaclMessage("Name fehlt.", "de"),
        new ShaclMessage("Name is required.", "en"));
  }

  /**
   * The same shape with the two {@code sh:message} lines swapped must yield the same set of
   * messages. Before the fix this flipped which single message a caller saw — the defect
   * reported in issue #20.
   */
  @Test
  void messageOrderInTheShapesGraphDoesNotChangeWhatIsReported() {
    Graph germanFirst = personShapeRequiringName(rdf.createLiteral("Name fehlt.", "de"),
        rdf.createLiteral("Name is required.", "en"));
    Graph englishFirst = personShapeRequiringName(rdf.createLiteral("Name is required.", "en"),
        rdf.createLiteral("Name fehlt.", "de"));

    Graph data = rdf.createGraph();
    data.add(ex("bob"), a(), ex("Person"));

    ShaclReport germanFirstReport = validation.validate(data, germanFirst, ValidationOptions.defaults());
    ShaclReport englishFirstReport = validation.validate(data, englishFirst, ValidationOptions.defaults());

    assertThat(germanFirstReport.results().get(0).messages())
        .containsExactlyInAnyOrderElementsOf(englishFirstReport.results().get(0).messages());
  }

  /** A plain, untagged {@code sh:message} arrives with no language tag rather than a blank one. */
  @Test
  void untaggedMessageArrivesWithoutALanguageTag() {
    Graph shapes = personShapeRequiringName(rdf.createLiteral("Name is required."));

    Graph data = rdf.createGraph();
    data.add(ex("bob"), a(), ex("Person"));

    ShaclReport report = validation.validate(data, shapes, ValidationOptions.defaults());

    ShaclMessage message = report.results().get(0).messages().get(0);
    assertThat(message.isUntagged()).isTrue();
    assertThat(message.language()).isNull();
    assertThat(message.text()).isEqualTo("Name is required.");
  }

  /**
   * A shapes graph may write a language tag in any case — BCP 47 says they mean the same
   * language. The tag arrives lower-cased, so a caller selecting with
   * {@code "de".equals(message.language())} finds a message tagged {@code @DE}.
   */
  @Test
  void languageTagArrivesLowerCasedWhateverTheShapesGraphWrote() {
    Graph shapes = personShapeRequiringName(rdf.createLiteral("Name fehlt.", "DE"),
        rdf.createLiteral("Name is required.", "en-GB"));

    Graph data = rdf.createGraph();
    data.add(ex("bob"), a(), ex("Person"));

    ShaclReport report = validation.validate(data, shapes, ValidationOptions.defaults());

    assertThat(report.results().get(0).messages()).extracting(ShaclMessage::language)
        .containsExactlyInAnyOrder("de", "en-gb");
  }

  /**
   * {@code sh:message} is optional in SHACL and the backend synthesizes none, so a result
   * without any message is reachable. It carries an empty list, never {@code null}.
   */
  @Test
  void resultWithoutAnyMessageCarriesAnEmptyList() {
    Graph shapes = personShapeRequiringName();

    Graph data = rdf.createGraph();
    data.add(ex("bob"), a(), ex("Person"));

    ShaclReport report = validation.validate(data, shapes, ValidationOptions.defaults());

    assertThat(report.results()).hasSize(1);
    assertThat(report.results().get(0).messages()).isEmpty();
  }

  /**
   * A shapes graph RDF4J's own shape parser rejects must surface as the neutral
   * {@link ShaclValidationException}, not RDF4J's own {@code RDF4JException} family — the
   * leak this test guards against (issue #66). {@code sh:path} here points at a blank node
   * that carries none of the recognised path-expression predicates ({@code sh:alternativePath},
   * {@code sh:inversePath}, ...), which RDF4J's {@code Path} parser rejects as an unknown path
   * type while building the validation plan.
   */
  @Test
  void unparsableShapesGraphSurfacesAsTheNeutralValidationException() {
    Graph shapes = rdf.createGraph();
    IRI personShape = ex("PersonShape");
    BlankNode nameProperty = rdf.createBlankNode();
    BlankNode malformedPath = rdf.createBlankNode();
    shapes.add(personShape, a(), sh("NodeShape"));
    shapes.add(personShape, sh("targetClass"), ex("Person"));
    shapes.add(personShape, sh("property"), nameProperty);
    shapes.add(nameProperty, sh("path"), malformedPath);
    shapes.add(nameProperty, sh("minCount"), rdf.createLiteral("1", xsdInteger()));
    // malformedPath carries no recognised path predicate (sh:alternativePath, sh:inversePath,
    // ...) -> RDF4J's Path parser rejects it as an unknown path type.
    shapes.add(malformedPath, ex("notAPathPredicate"), ex("irrelevant"));

    Graph data = rdf.createGraph();
    data.add(ex("bob"), a(), ex("Person"));

    assertThatThrownBy(() -> validation.validate(data, shapes, ValidationOptions.defaults()))
        .isInstanceOf(ShaclValidationException.class)
        .hasCauseInstanceOf(RDF4JException.class)
        .hasRootCauseInstanceOf(ShaclUnsupportedException.class);
  }

  /**
   * A literal whose lexical form does not fit its datatype must surface as the neutral
   * {@link ShaclValidationException} too. {@code rdf-terms} accepts such a literal — it
   * models RDF without validating lexical forms — while RDF4J's {@code ValidatingValueFactory}
   * rejects it while the graph is being handed to the backend, i.e. before validation even
   * starts. That step sits inside the translated region as well, so no bare
   * {@link IllegalArgumentException} reaches the caller.
   */
  @Test
  void literalWithAnInvalidLexicalFormSurfacesAsTheNeutralValidationException() {
    Graph shapes = personShapeRequiringName();

    Graph data = rdf.createGraph();
    data.add(ex("bob"), a(), ex("Person"));
    data.add(ex("bob"), ex("age"), rdf.createLiteral("not-a-number", xsdInteger()));

    assertThatThrownBy(() -> validation.validate(data, shapes, ValidationOptions.defaults()))
        .isInstanceOf(ShaclValidationException.class)
        .hasMessageContaining("data graph")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  /** The same conversion failure in the shapes graph is named as such. */
  @Test
  void invalidLexicalFormInTheShapesGraphNamesTheShapesGraph() {
    Graph shapes = rdf.createGraph();
    IRI personShape = ex("PersonShape");
    BlankNode nameProperty = rdf.createBlankNode();
    shapes.add(personShape, a(), sh("NodeShape"));
    shapes.add(personShape, sh("targetClass"), ex("Person"));
    shapes.add(personShape, sh("property"), nameProperty);
    shapes.add(nameProperty, sh("path"), ex("name"));
    shapes.add(nameProperty, sh("minCount"), rdf.createLiteral("not-a-number", xsdInteger()));

    Graph data = rdf.createGraph();
    data.add(ex("bob"), a(), ex("Person"));

    assertThatThrownBy(() -> validation.validate(data, shapes, ValidationOptions.defaults()))
        .isInstanceOf(ShaclValidationException.class)
        .hasMessageContaining("shapes graph")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  private Graph personShapeRequiringName(Literal... messages) {
    Graph shapes = rdf.createGraph();
    IRI personShape = ex("PersonShape");
    BlankNode nameProperty = rdf.createBlankNode();
    shapes.add(personShape, a(), sh("NodeShape"));
    shapes.add(personShape, sh("targetClass"), ex("Person"));
    shapes.add(personShape, sh("property"), nameProperty);
    shapes.add(nameProperty, sh("path"), ex("name"));
    shapes.add(nameProperty, sh("minCount"), rdf.createLiteral("1", xsdInteger()));
    for (Literal message : messages) {
      shapes.add(nameProperty, sh("message"), message);
    }
    return shapes;
  }

  private Graph animalShapeRequiringName() {
    Graph shapes = animalShapeRequiringNameWithoutATarget();
    shapes.add(ex("AnimalShape"), sh("targetClass"), ex("Animal"));
    return shapes;
  }

  private Graph animalShapeRequiringNameWithoutATarget() {
    Graph shapes = rdf.createGraph();
    IRI animalShape = ex("AnimalShape");
    BlankNode nameProperty = rdf.createBlankNode();
    shapes.add(animalShape, a(), sh("NodeShape"));
    shapes.add(animalShape, sh("property"), nameProperty);
    shapes.add(nameProperty, sh("path"), ex("name"));
    shapes.add(nameProperty, sh("minCount"), rdf.createLiteral("1", xsdInteger()));
    return shapes;
  }
}
