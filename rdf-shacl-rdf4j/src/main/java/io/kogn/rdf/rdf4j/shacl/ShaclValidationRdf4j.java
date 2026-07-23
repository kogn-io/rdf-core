// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.shacl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.SHACL;
import org.eclipse.rdf4j.sail.Sail;
import org.eclipse.rdf4j.sail.SailConnection;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.shacl.ShaclValidator;
import org.eclipse.rdf4j.sail.shacl.results.ValidationReport;

import io.kogn.rdf.rdf4j.shacl.internal.GraphModelConverter;
import io.kogn.rdf.shacl.Severity;
import io.kogn.rdf.shacl.ShaclMessage;
import io.kogn.rdf.shacl.ShaclReport;
import io.kogn.rdf.shacl.ShaclResult;
import io.kogn.rdf.shacl.ShaclValidation;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * RDF4J-based implementation of {@link ShaclValidation}, wrapping
 * {@link ShaclValidator}.
 *
 * <p>Data and shapes graphs are loaded into transient, in-memory {@link MemoryStore}
 * sails for the duration of a single {@link #validate} call; nothing is persisted and
 * no state is shared across calls, matching the stateless, non-transactional contract
 * of the port.</p>
 *
 * <h2>Where RDFS axioms may live</h2>
 *
 * <p>With {@link ValidationOptions#rdfsSubClassReasoning()} enabled, this adapter picks the
 * {@code rdfs:subClassOf} axioms up from <em>either</em> input graph — they may sit with the
 * {@code data} or with the {@code shapes}, and both make a shape targeting a superclass fire
 * on subclass-typed instances. Consumers therefore need not merge ontology axioms into their
 * candidate data. Note the axioms must be present <em>somewhere</em>: without them the option
 * is a silent no-op, as described on {@link ValidationOptions}.</p>
 *
 * <h2>Conformance differs from RDF4J's own {@code ValidationReport.conforms()}</h2>
 *
 * <p>RDF4J's native report considers <em>any</em> reported result — regardless of
 * severity — as non-conforming. This adapter instead computes {@link ShaclReport#conforms()}
 * itself: only {@link Severity#VIOLATION} results make the report non-conforming;
 * {@code sh:Warning} and {@code sh:Info} results are carried in
 * {@link ShaclReport#results()} but never flip {@code conforms} to {@code false}.</p>
 *
 * <h2>Every {@code sh:resultMessage} is mapped</h2>
 *
 * <p>All {@code sh:resultMessage} statements of a validation result reach
 * {@link ShaclResult#messages()} as {@link io.kogn.rdf.shacl.ShaclMessage}s with their
 * language tags intact — a shape carrying one message per language surfaces all of them,
 * and this adapter selects none. Their order is whatever the underlying report model
 * yields (in practice the parse order of the shapes graph) and carries no meaning.</p>
 */
public final class ShaclValidationRdf4j implements ShaclValidation {

  /** Creates a new RDF4J-backed SHACL validator. */
  public ShaclValidationRdf4j() {
  }

  @Override
  public ShaclReport validate(ReadableGraph data, ReadableGraph shapes, ValidationOptions options) {
    Objects.requireNonNull(data, "data must not be null");
    Objects.requireNonNull(shapes, "shapes must not be null");
    Objects.requireNonNull(options, "options must not be null");

    Model dataModel = GraphModelConverter.toModel(data);
    Model shapesModel = GraphModelConverter.toModel(shapes);

    Sail dataSail = toSail(dataModel);
    Sail shapesSail = toSail(shapesModel);
    try {
      ValidationReport report = ShaclValidator.builder()
          .setRdfsSubClassReasoning(options.rdfsSubClassReasoning())
          .withShapes(shapesSail)
          .build()
          .validate(dataSail);
      return toShaclReport(report);
    } finally {
      shapesSail.shutDown();
      dataSail.shutDown();
    }
  }

  private static Sail toSail(Model model) {
    Sail sail = new MemoryStore();
    sail.init();
    try (SailConnection connection = sail.getConnection()) {
      connection.begin();
      for (Statement statement : model) {
        connection.addStatement(statement.getSubject(), statement.getPredicate(), statement.getObject());
      }
      connection.commit();
    }
    return sail;
  }

  private static ShaclReport toShaclReport(ValidationReport report) {
    Model model = report.asModel();
    List<ShaclResult> results = model.filter(null, RDF.TYPE, SHACL.VALIDATION_RESULT)
        .subjects()
        .stream()
        .map(resultId -> toShaclResult(model, resultId))
        .toList();
    boolean conforms = results.stream().noneMatch(result -> result.severity() == Severity.VIOLATION);
    return new ShaclReport(conforms, results);
  }

  private static ShaclResult toShaclResult(Model model, Resource resultId) {
    String focusNode = firstObject(model, resultId, SHACL.FOCUS_NODE).map(Value::stringValue)
        .orElseThrow(() -> new IllegalStateException("SHACL validation result without sh:focusNode: " + resultId));
    String path = firstObject(model, resultId, SHACL.RESULT_PATH).map(Value::stringValue).orElse(null);
    Severity severity = firstObject(model, resultId, SHACL.RESULT_SEVERITY).map(ShaclValidationRdf4j::toSeverity)
        .orElse(Severity.VIOLATION);
    List<ShaclMessage> messages = model.filter(resultId, SHACL.RESULT_MESSAGE, null)
        .stream()
        .map(Statement::getObject)
        .map(ShaclValidationRdf4j::toShaclMessage)
        .toList();
    return new ShaclResult(focusNode, path, severity, messages);
  }

  /**
   * Maps one {@code sh:resultMessage} object, keeping its language tag.
   *
   * <p>A tag reported as blank is mapped to {@code null} here rather than handed on.
   * That is not a disagreement with {@link ShaclMessage}, which <em>rejects</em> a blank
   * tag: the type stays strict because {@code ""} is not a language, and this boundary
   * maps to "untagged" beforehand so a backend artifact would degrade to an untagged
   * message instead of throwing out of a validation run. RDF4J does not produce such a
   * literal — an {@code rdf:langString} with an empty tag is not constructible through
   * Rio — so no test reaches this branch; it guards the port's invariant against a
   * backend that behaves differently, at no cost.</p>
   */
  private static ShaclMessage toShaclMessage(Value message) {
    if (message instanceof Literal literal) {
      String language = literal.getLanguage().filter(tag -> !tag.isBlank()).orElse(null);
      return new ShaclMessage(literal.getLabel(), language);
    }
    return ShaclMessage.untagged(message.stringValue());
  }

  private static Severity toSeverity(Value severityIri) {
    if (SHACL.WARNING.equals(severityIri)) {
      return Severity.WARNING;
    }
    if (SHACL.INFO.equals(severityIri)) {
      return Severity.INFO;
    }
    return Severity.VIOLATION;
  }

  private static Optional<Value> firstObject(Model model, Resource subject, IRI predicate) {
    return model.filter(subject, predicate, null).stream().map(Statement::getObject).findFirst();
  }
}
