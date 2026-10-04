// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.shacl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.DASH;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.SHACL;
import org.eclipse.rdf4j.sail.Sail;
import org.eclipse.rdf4j.sail.SailConnection;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.shacl.ShaclValidator;
import org.eclipse.rdf4j.sail.shacl.ast.ShaclUnsupportedException;
import org.eclipse.rdf4j.sail.shacl.results.ValidationReport;

import io.kogn.rdf.rdf4j.shacl.internal.GraphModelConverter;
import io.kogn.rdf.shacl.Severity;
import io.kogn.rdf.shacl.ShaclMessage;
import io.kogn.rdf.shacl.ShaclReport;
import io.kogn.rdf.shacl.ShaclResult;
import io.kogn.rdf.shacl.ShaclValidation;
import io.kogn.rdf.shacl.ShaclValidationException;
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
 * <h2>RDF4J's proprietary SHACL extensions are switched off</h2>
 *
 * <p>This adapter always builds with {@code setEclipseRdf4jShaclExtensions(false)}, which
 * makes RDF4J's {@code http://rdf4j.org/shacl-extensions#} vocabulary (below:
 * {@code rdf4j-ext:}) inert <em>as a whole</em>: a shapes graph may carry those predicates
 * and gets no error for it, they simply carry no meaning here. Two consequences are worth
 * naming:</p>
 *
 * <ul>
 *   <li>{@code rdf4j-ext:rdfsSubClassReasoning} on a shape does not override
 *       {@link ValidationOptions} for that shape. {@link ValidationOptions} is the sole
 *       authority over reasoning behavior; a shapes graph cannot silently flip a setting the
 *       caller made explicit.</li>
 *   <li>{@code rdf4j-ext:targetShape} selects nothing, so a shape whose <em>only</em> target
 *       is that predicate has no target at all and never fires. The failure mode is a
 *       <strong>silently conforming report</strong>, not an error — shapes that relied on it
 *       have to target with standard SHACL instead ({@code sh:targetClass},
 *       {@code sh:targetNode}, {@code sh:targetSubjectsOf}, {@code sh:targetObjectsOf}).
 *       {@code rdf4j-ext:includeInferredStatements} is inert likewise, though it would change
 *       nothing here anyway: both graphs are loaded into plain in-memory sails with no
 *       inferencer, so there are no inferred statements to include.</li>
 * </ul>
 *
 * <p>Dropping the whole vocabulary is deliberate for a backend-neutral port: a second SHACL
 * engine would not know these predicates, so honoring them here would make identical shapes
 * with identical {@link ValidationOptions} validate differently per backend — exactly the
 * leak the port exists to prevent.</p>
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
 *
 * <h2>RDF lists in the shapes graph must be well-formed</h2>
 *
 * <p>Every value of a list-valued SHACL parameter ({@code sh:in}, {@code sh:languageIn},
 * {@code sh:and}, {@code sh:or}, {@code sh:xone}, {@code sh:ignoredProperties},
 * {@code sh:alternativePath}, DASH's {@code dash:hasValueIn}) and every sequence path must be
 * a well-formed RDF list: each cell carries exactly one {@code rdf:first} and one
 * {@code rdf:rest}, and following {@code rdf:rest} reaches {@code rdf:nil} without visiting a
 * cell twice. A shapes graph that breaks this is rejected with a
 * {@link ShaclValidationException} before RDF4J sees it — RDF4J itself walks such a list
 * until the heap is exhausted.</p>
 */
public final class ShaclValidationRdf4j implements ShaclValidation {

  /** SHACL (and DASH) parameters whose value RDF4J reads as an RDF list. */
  private static final List<IRI> LIST_PARAMETERS = List.of(SHACL.IN, SHACL.LANGUAGE_IN, SHACL.AND, SHACL.OR, SHACL.XONE,
      SHACL.IGNORED_PROPERTIES, SHACL.ALTERNATIVE_PATH, DASH.hasValueIn);

  /** Path operators whose operand is itself a path expression. */
  private static final List<IRI> PATH_OPERATORS = List.of(SHACL.INVERSE_PATH, SHACL.ZERO_OR_MORE_PATH,
      SHACL.ONE_OR_MORE_PATH, SHACL.ZERO_OR_ONE_PATH);

  /** Creates a new RDF4J-backed SHACL validator. */
  public ShaclValidationRdf4j() {
  }

  @Override
  public ShaclReport validate(ReadableGraph data, ReadableGraph shapes, ValidationOptions options) {
    Objects.requireNonNull(data, "data must not be null");
    Objects.requireNonNull(shapes, "shapes must not be null");
    Objects.requireNonNull(options, "options must not be null");

    Sail dataSail = null;
    Sail shapesSail = null;
    try {
      dataSail = toSail(toModel(data, "data"));
      Model shapesModel = toModel(shapes, "shapes");
      requireWellFormedLists(shapesModel);
      shapesSail = toSail(shapesModel);
      ValidationReport report = ShaclValidator.builder()
          .setRdfsSubClassReasoning(options.rdfsSubClassReasoning())
          .setEclipseRdf4jShaclExtensions(false)
          .withShapes(shapesSail)
          .build()
          .validate(dataSail);
      return toShaclReport(report);
    } catch (RDF4JException | ShaclUnsupportedException e) {
      // ShaclUnsupportedException extends UnsupportedOperationException, not RDF4JException,
      // so it needs its own alternative. RDF4J has only ever been observed handing it over
      // wrapped in a ShaclShapeParsingException; the alternative is the defence against the
      // unwrapped case rather than a path a test can reach.
      throw new ShaclValidationException("SHACL validation could not be completed: " + e.getMessage(), e);
    } finally {
      if (shapesSail != null) {
        shapesSail.shutDown();
      }
      if (dataSail != null) {
        dataSail.shutDown();
      }
    }
  }

  /**
   * Converts one input graph into an RDF4J model.
   *
   * <p>The conversion is where a term {@code rdf-terms} accepts but RDF4J does not — a
   * literal whose lexical form does not fit its datatype, say — fails, as an
   * {@link IllegalArgumentException} out of RDF4J's validating value factory. That is a
   * backend disagreement about the input, not a defect in the caller's call, so it is
   * translated like any other reason no report can be produced; {@code role} names which
   * of the two graphs was at fault, which the exception itself does not say.</p>
   */
  private static Model toModel(ReadableGraph graph, String role) {
    try {
      return GraphModelConverter.toModel(graph);
    } catch (IllegalArgumentException e) {
      throw new ShaclValidationException("the " + role + " graph could not be handed to the backend: " + e.getMessage(),
          e);
    }
  }

  /**
   * Rejects a shapes graph whose list-valued parameters or sequence paths are not
   * well-formed RDF lists, as described on the class.
   *
   * <p>The check runs over the whole shapes graph, not just the shapes RDF4J would pick up,
   * so a stray malformed {@code sh:in} that no shape reaches is rejected too.</p>
   */
  private static void requireWellFormedLists(Model shapes) {
    for (IRI parameter : LIST_PARAMETERS) {
      for (Statement statement : shapes.filter(null, parameter, null)) {
        List<Value> elements = listElements(shapes, statement.getObject(), parameter);
        if (SHACL.ALTERNATIVE_PATH.equals(parameter)) {
          elements.forEach(element -> requireWellFormedPath(shapes, element, new HashSet<>()));
        }
      }
    }
    for (Value path : shapes.filter(null, SHACL.PATH, null).objects()) {
      requireWellFormedPath(shapes, path, new HashSet<>());
    }
  }

  /**
   * Walks one path expression: a node carrying {@code rdf:first} or {@code rdf:rest} is a
   * sequence path and must be a well-formed list; the operands of the path operators are
   * walked in turn. {@code visited} keeps this walk finite on a cyclic path expression.
   */
  private static void requireWellFormedPath(Model shapes, Value path, Set<Resource> visited) {
    if (!(path instanceof Resource node) || !visited.add(node)) {
      return;
    }
    if (shapes.contains(node, RDF.FIRST, null) || shapes.contains(node, RDF.REST, null)) {
      listElements(shapes, node, SHACL.PATH).forEach(element -> requireWellFormedPath(shapes, element, visited));
    }
    for (IRI operator : PATH_OPERATORS) {
      shapes.filter(node, operator, null).objects().forEach(operand -> requireWellFormedPath(shapes, operand, visited));
    }
  }

  private static List<Value> listElements(Model shapes, Value head, IRI parameter) {
    List<Value> elements = new ArrayList<>();
    Set<Resource> cells = new HashSet<>();
    Value current = head;
    while (!RDF.NIL.equals(current)) {
      if (!(current instanceof Resource cell)) {
        throw malformedList(parameter, head, "a list cell is the literal " + current);
      }
      if (!cells.add(cell)) {
        throw malformedList(parameter, head, "it is cyclic, cell " + cell + " is reached twice");
      }
      Set<Value> firsts = shapes.filter(cell, RDF.FIRST, null).objects();
      Set<Value> rests = shapes.filter(cell, RDF.REST, null).objects();
      if (firsts.size() != 1 || rests.size() != 1) {
        throw malformedList(parameter, head, "cell " + cell + " has " + firsts.size() + " rdf:first and " + rests.size()
            + " rdf:rest values instead of exactly one each");
      }
      elements.add(firsts.iterator().next());
      current = rests.iterator().next();
    }
    return elements;
  }

  private static ShaclValidationException malformedList(IRI parameter, Value head, String reason) {
    return new ShaclValidationException(
        "the shapes graph holds a malformed RDF list as a value of " + parameter + " (list " + head + "): " + reason,
        null);
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
