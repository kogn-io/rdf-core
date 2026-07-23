// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.shacl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.shacl.Severity;
import io.kogn.rdf.shacl.ShaclMessage;
import io.kogn.rdf.shacl.ShaclReport;
import io.kogn.rdf.shacl.ShaclResult;
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
    Graph shapes = rdf.createGraph();
    IRI animalShape = ex("AnimalShape");
    BlankNode nameProperty = rdf.createBlankNode();
    shapes.add(animalShape, a(), sh("NodeShape"));
    shapes.add(animalShape, sh("targetClass"), ex("Animal"));
    shapes.add(animalShape, sh("property"), nameProperty);
    shapes.add(nameProperty, sh("path"), ex("name"));
    shapes.add(nameProperty, sh("minCount"), rdf.createLiteral("1", xsdInteger()));
    return shapes;
  }
}
