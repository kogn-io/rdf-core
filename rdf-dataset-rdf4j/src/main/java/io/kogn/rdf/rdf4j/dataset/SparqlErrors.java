// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.Operation;

import io.kogn.rdf.dataset.MalformedSparqlException;
import io.kogn.rdf.dataset.SparqlEvaluationException;
import io.kogn.rdf.rdf4j.internal.RDF4JConverters;
import io.kogn.rdf.terms.RDFTerm;

/**
 * Translates RDF4J SPARQL failures into the neutral dataset-port exceptions, and applies
 * pre-bound variables to a prepared operation.
 *
 * <p>A SPARQL call can fail at two stages. Preparation ({@code prepareTupleQuery} and friends)
 * throws RDF4J's {@link MalformedQueryException} for a string that does not parse. Evaluation —
 * {@code evaluate()}/{@code execute()}, and reading the result afterwards — throws
 * {@code QueryEvaluationException} or {@code UpdateExecutionException} for a well-formed
 * operation the backend could not carry out, such as a {@code SERVICE} clause against an
 * unreachable endpoint. No backend type may reach a consumer of the {@code io.kogn.rdf.dataset}
 * ports, so every RDF4J call of a SPARQL operation is routed through
 * {@link #translating(Supplier)} (or {@link #executing(Runnable)} for an update), which rethrows
 * a parse failure as the neutral {@link MalformedSparqlException} and every other failure as the
 * neutral {@link SparqlEvaluationException}, with the original kept as cause.</p>
 *
 * <p>The catch is {@link RuntimeException}, not RDF4J's own {@code RDF4JException}, for the same
 * reason {@code DatasetExportRdf4j} catches broadly: a sail — an inferencer, a federation — may
 * signal through a foreign unchecked exception that {@code SailRepositoryConnection} passes on
 * unwrapped. What makes the blanket catch safe is what the callers keep <em>out</em> of it:
 * binding the caller's terms ({@link #bound}) and converting the RDF4J result into port types
 * both run outside, so a defect in this library — or a {@code null} binding value the port
 * forbids — is never disguised as an evaluation failure. Only RDF4J calls go in.</p>
 */
final class SparqlErrors {

  private SparqlErrors() {
  }

  /**
   * Runs an RDF4J call of a SPARQL operation, translating its failures into the neutral port
   * exceptions.
   *
   * @param <T> the type of the call's result
   * @param backendCall the RDF4J preparation, or the RDF4J evaluation together with reading its
   *     result to the end; must contain nothing but RDF4J calls (see the class Javadoc)
   * @return whatever the call returns
   * @throws MalformedSparqlException if RDF4J rejects the string as malformed
   * @throws SparqlEvaluationException if the call fails in any other way
   */
  static <T> T translating(final Supplier<T> backendCall) {
    try {
      return backendCall.get();
    } catch (final MalformedQueryException e) {
      throw new MalformedSparqlException(e.getMessage(), e);
    } catch (final RuntimeException e) {
      throw new SparqlEvaluationException(e.getMessage(), e);
    }
  }

  /**
   * Runs the RDF4J execution of a SPARQL update, translating its failures like
   * {@link #translating(Supplier)}.
   *
   * @param backendCall the RDF4J {@code execute()} call; must contain nothing but RDF4J calls
   * @throws MalformedSparqlException if RDF4J rejects the string as malformed
   * @throws SparqlEvaluationException if the execution fails in any other way
   */
  static void executing(final Runnable backendCall) {
    translating(() -> {
      backendCall.run();
      return null;
    });
  }

  /**
   * Applies pre-bound variables to a prepared operation via
   * {@link Operation#setBinding(String, org.eclipse.rdf4j.model.Value)}.
   *
   * @param <T> the type of the prepared operation
   * @param operation the prepared query or update to bind against
   * @param bindings variable name (without the leading {@code ?}) to value; must not be
   *     {@code null}
   * @return {@code operation}, for chaining
   */
  static <T extends Operation> T bound(final T operation, final Map<String, RDFTerm> bindings) {
    bindings.forEach((name, value) -> operation.setBinding(name, RDF4JConverters.toRDF4JValue(value)));
    return operation;
  }
}
