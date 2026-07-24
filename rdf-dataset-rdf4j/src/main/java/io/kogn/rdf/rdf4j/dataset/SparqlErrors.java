// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.util.function.Supplier;

import org.eclipse.rdf4j.query.MalformedQueryException;

import io.kogn.rdf.dataset.MalformedSparqlException;

/**
 * Translates RDF4J parse failures into the neutral dataset-port exceptions.
 *
 * <p>Query and update preparation ({@code prepareTupleQuery} and friends) throws
 * RDF4J's {@link MalformedQueryException}. That backend type must not reach a
 * consumer of the {@code io.kogn.rdf.dataset} ports, so every preparation call is
 * routed through {@link #preparing(Supplier)}, which rethrows it as the neutral
 * {@link MalformedSparqlException} with the original kept as cause.
 */
final class SparqlErrors {

  private SparqlErrors() {
  }

  /**
   * Runs a SPARQL preparation step, translating a malformed-query failure.
   *
   * @param <T> the type of the prepared operation
   * @param preparation the RDF4J {@code prepare…} call to run
   * @return whatever the preparation returns
   * @throws MalformedSparqlException if RDF4J rejects the string as malformed
   */
  static <T> T preparing(final Supplier<T> preparation) {
    try {
      return preparation.get();
    } catch (final MalformedQueryException e) {
      throw new MalformedSparqlException(e.getMessage(), e);
    }
  }
}
