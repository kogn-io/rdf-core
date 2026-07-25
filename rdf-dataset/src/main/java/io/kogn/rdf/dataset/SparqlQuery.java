// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

import java.util.Map;
import java.util.stream.Stream;

import io.kogn.rdf.terms.RDFTerm;
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
 *
 * <p>Every query form has a bindings overload (see {@link #select(String, Map)},
 * {@link #construct(String, Map)}, {@link #ask(String, Map)}): each entry substitutes a
 * pre-bound {@link RDFTerm} value for the matching {@code ?variable} before the query
 * runs, the way {@code PreparedStatement} parameters substitute for {@code ?} in JDBC.
 * A term embedded this way is never parsed as SPARQL syntax, so a caller assembling a
 * query around a value it does not fully control — an IRI or a literal from outside the
 * program — should bind it rather than concatenate it into the query string, which
 * avoids both SPARQL injection and having to hand-escape the term's lexical form.</p>
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
   * Executes a SPARQL SELECT query with pre-bound variables and returns a stream of
   * binding sets.
   *
   * <p>Equivalent to {@link #select(String)}, except that each entry of
   * {@code bindings} substitutes its value for the same-named {@code ?variable} in
   * {@code sparql} before evaluation — see the class-level Javadoc for why this is
   * preferable to concatenating the value into the query string.</p>
   *
   * @param sparql the SPARQL SELECT query string; must not be {@code null} or empty
   * @param bindings variable name (without the leading {@code ?}) to value; must not be
   *     {@code null}; an empty map behaves like {@link #select(String)}
   * @return a stream of binding sets; never {@code null}
   * @throws MalformedSparqlException if the SPARQL string is syntactically invalid
   */
  Stream<BindingSet> select(String sparql, Map<String, RDFTerm> bindings);

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
   * Executes a SPARQL CONSTRUCT query with pre-bound variables and returns the
   * resulting graph.
   *
   * <p>Equivalent to {@link #construct(String)}, except that each entry of
   * {@code bindings} substitutes its value for the same-named {@code ?variable} in
   * {@code sparql} before evaluation — see the class-level Javadoc for why this is
   * preferable to concatenating the value into the query string.</p>
   *
   * @param sparql the SPARQL CONSTRUCT query string; must not be {@code null} or empty
   * @param bindings variable name (without the leading {@code ?}) to value; must not be
   *     {@code null}; an empty map behaves like {@link #construct(String)}
   * @return the constructed graph; never {@code null}
   * @throws MalformedSparqlException if the SPARQL string is syntactically invalid
   */
  ReadableGraph construct(String sparql, Map<String, RDFTerm> bindings);

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

  /**
   * Executes a SPARQL ASK query with pre-bound variables and returns its boolean
   * result.
   *
   * <p>Equivalent to {@link #ask(String)}, except that each entry of {@code bindings}
   * substitutes its value for the same-named {@code ?variable} in {@code sparql}
   * before evaluation — see the class-level Javadoc for why this is preferable to
   * concatenating the value into the query string.</p>
   *
   * @param sparql the SPARQL ASK query string; must not be {@code null} or empty
   * @param bindings variable name (without the leading {@code ?}) to value; must not be
   *     {@code null}; an empty map behaves like {@link #ask(String)}
   * @return {@code true} if the pattern has at least one match
   * @throws MalformedSparqlException if the SPARQL string is syntactically invalid
   */
  boolean ask(String sparql, Map<String, RDFTerm> bindings);
}
