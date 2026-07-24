// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

import java.util.stream.Stream;

import io.kogn.rdf.terms.ReadableGraph;

/**
 * SPARQL read port — non-transactional query access to a dataset.
 *
 * <p>Covers three of the four SPARQL 1.1 query forms that read data: {@code SELECT},
 * {@code CONSTRUCT} and {@code ASK} ({@code DESCRIBE} is not supported). Each
 * operation reads a consistent snapshot
 * of the dataset and never mutates it; for write operations see
 * {@link SparqlUpdate}, and for queries that must participate in an atomic
 * unit-of-work alongside writes see {@link DatasetTx}.</p>
 *
 * <p>Operations run outside of any caller-visible transaction: each call is a
 * self-contained read against the current committed state of the store.</p>
 */
public interface SparqlQuery {

  /**
   * Executes a SPARQL SELECT query and returns a stream of binding sets.
   *
   * <p>Each element of the stream represents one row of the SELECT result, with
   * variable names mapped to {@link io.kogn.rdf.terms.RDFTerm} values via
   * {@link BindingSet}. The returned stream is fully materialised and may be
   * consumed after this method returns; no store resources are held open.</p>
   *
   * @param sparql the SPARQL SELECT query string; must not be {@code null} or empty
   * @return a stream of binding sets; never {@code null}
   * @throws MalformedSparqlException if the SPARQL string is syntactically invalid
   */
  Stream<BindingSet> select(String sparql);

  /**
   * Executes a SPARQL CONSTRUCT query and returns the resulting graph.
   *
   * <p>The graph contains the triples produced by the CONSTRUCT template and
   * pattern matching against the current committed state of the dataset.</p>
   *
   * @param sparql the SPARQL CONSTRUCT query string; must not be {@code null} or empty
   * @return the constructed graph; never {@code null}
   * @throws MalformedSparqlException if the SPARQL string is syntactically invalid
   */
  ReadableGraph construct(String sparql);

  /**
   * Executes a SPARQL ASK query and returns its boolean result.
   *
   * <p>Returns {@code true} if the query pattern matches at least one solution in
   * the dataset, {@code false} otherwise.</p>
   *
   * @param sparql the SPARQL ASK query string; must not be {@code null} or empty
   * @return {@code true} if the pattern has at least one match
   * @throws MalformedSparqlException if the SPARQL string is syntactically invalid
   */
  boolean ask(String sparql);
}
