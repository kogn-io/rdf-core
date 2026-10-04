// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

import java.util.Map;

import io.kogn.rdf.terms.RDFTerm;

/**
 * SPARQL write port.
 *
 * <p>Covers write operations via the SPARQL 1.1 Update language. This port does
 * not read data; for {@code SELECT}, {@code CONSTRUCT} and {@code ASK} queries
 * see {@link SparqlQuery}, and for writes that participate in an atomic
 * unit-of-work see {@link DatasetTx}.</p>
 *
 * <p>{@link #update(String, Map)} pre-binds variables the way
 * {@link SparqlQuery#select(String, Map) SparqlQuery}'s bindings overloads do — see
 * that class's Javadoc for why binding a value is preferable to concatenating it into
 * the update string.</p>
 *
 * <p><strong>Caller must address named graphs explicitly.</strong> The dataset model has
 * no default graph (see the package documentation), but this port does not parse or
 * police the SPARQL text it is given: an {@code INSERT DATA} (or {@code DELETE DATA},
 * {@code INSERT/DELETE WHERE}) without a {@code GRAPH} clause or {@code WITH} still
 * executes, and lands its statements in the underlying store's default graph — a
 * location none of the other dataset ports can read back. Every update must therefore
 * name its target graph explicitly.</p>
 *
 * <p><strong>The delete side is wider than the insert side.</strong> A {@code DELETE DATA},
 * a {@code DELETE WHERE} or a {@code DELETE {...} WHERE {...}} template (also combined with
 * {@code INSERT}) without a {@code GRAPH} clause or {@code WITH} is not confined to the default
 * graph: it removes every matching triple from <em>each</em> named graph that holds it (and
 * from the default graph), following the union-default-graph semantics of the RDF4J
 * backend. A graph-less delete aimed at one named graph therefore silently removes the same
 * triple from all the others.</p>
 *
 * <p><strong>RDF 1.2 triple terms.</strong> An update can store an RDF 1.2 triple term, which
 * the port data model cannot represent; the update itself is not rejected. Reading such
 * content back through {@link GraphStore#export(io.kogn.rdf.terms.IRI)} or {@link SparqlQuery}
 * fails with an {@link IllegalStateException}.</p>
 */
public interface SparqlUpdate {

  /**
   * Executes a SPARQL 1.1 Update operation against the dataset.
   *
   * <p>Typical operations include {@code INSERT DATA}, {@code DELETE DATA},
   * {@code INSERT/DELETE WHERE}, and {@code CLEAR}. The operation is executed
   * atomically if the underlying store supports it.</p>
   *
   * @param sparql the SPARQL Update string; must not be {@code null} or empty
   * @throws MalformedSparqlException if the SPARQL string is syntactically invalid
   * @throws SparqlEvaluationException if the SPARQL string is well-formed but the backend fails
   *     while evaluating it
   */
  void update(String sparql);

  /**
   * Executes a SPARQL 1.1 Update operation with pre-bound variables against the
   * dataset.
   *
   * <p>Equivalent to {@link #update(String)}, except that each entry of
   * {@code bindings} substitutes its value for the same-named {@code ?variable} in
   * {@code sparql} before execution.</p>
   *
   * @param sparql the SPARQL Update string; must not be {@code null} or empty
   * @param bindings variable name (without the leading {@code ?}) to value; must not be
   *     {@code null}; no entry's value may be {@code null} either; an empty map behaves like
   *     {@link #update(String)}
   * @throws MalformedSparqlException if the SPARQL string is syntactically invalid
   * @throws SparqlEvaluationException if the SPARQL string is well-formed but the backend fails
   *     while evaluating it
   */
  void update(String sparql, Map<String, RDFTerm> bindings);
}
