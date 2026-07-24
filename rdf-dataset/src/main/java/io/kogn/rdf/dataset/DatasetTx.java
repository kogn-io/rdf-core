// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

import java.util.stream.Stream;

import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * Dataset operations available within a {@link DatasetTransactor} transaction.
 *
 * <p>All operations in this interface participate in the surrounding atomic
 * unit-of-work. Implementations must guarantee that either all operations are
 * committed together or none are (full rollback on any exception).</p>
 *
 * <p>Instances are created and managed by {@link DatasetTransactor#inTransaction}
 * and must not be used outside the scope of that call.</p>
 */
public interface DatasetTx {

  /**
   * Adds all triples in the given graph to the named graph within this transaction.
   *
   * <p>Duplicate triples are silently ignored. The named graph is created implicitly
   * if it does not exist.</p>
   *
   * @param namedGraph IRI identifying the target named graph; must not be {@code null}
   * @param triples the triples to add; must not be {@code null}
   */
  void add(IRI namedGraph, ReadableGraph triples);

  /**
   * Removes all triples in the given graph from the named graph within this transaction.
   *
   * <p>Triples not present in the graph are silently ignored.</p>
   *
   * @param namedGraph IRI identifying the target named graph; must not be {@code null}
   * @param triples the triples to remove; must not be {@code null}
   */
  void remove(IRI namedGraph, ReadableGraph triples);

  /**
   * Removes all triples from the named graph within this transaction.
   *
   * @param namedGraph IRI identifying the named graph to clear; must not be {@code null}
   */
  void clear(IRI namedGraph);

  /**
   * Executes a SPARQL 1.1 Update operation within this transaction.
   *
   * @param sparql the SPARQL Update string; must not be {@code null} or empty
   * @throws MalformedSparqlException if the SPARQL string is syntactically invalid
   */
  void update(String sparql);

  /**
   * Executes a SPARQL SELECT query and returns a lazily-evaluated stream of binding sets.
   *
   * <p>Each element of the stream represents one row of the SELECT result, with
   * variable names mapped to {@link io.kogn.rdf.terms.RDFTerm} values via
   * {@link BindingSet}.</p>
   *
   * <p>The stream must be consumed within the transaction scope; holding it open
   * after {@link DatasetTransactor#inTransaction} returns is undefined behaviour.</p>
   *
   * @param sparql the SPARQL SELECT query string; must not be {@code null} or empty
   * @return a lazily-evaluated stream of binding sets; never {@code null}
   * @throws MalformedSparqlException if the SPARQL string is syntactically invalid
   */
  Stream<BindingSet> select(String sparql);

  /**
   * Checks whether the named graph contains a triple matching the given pattern,
   * without going through SPARQL.
   *
   * <p>Use {@code null} as a wildcard for any of subject, predicate and object,
   * following {@link ReadableGraph#stream(io.kogn.rdf.terms.BlankNodeOrIRI, IRI,
   * io.kogn.rdf.terms.RDFTerm)}. {@code contains(g, s, p, null)} therefore asks
   * "does {@code s} already have any value for {@code p} in {@code g}?".</p>
   *
   * <p><strong>Prefer this over {@link #ask(String)} for optimistic-concurrency
   * guards.</strong> Whether a guard read is protected by the transaction's isolation
   * level depends on how the backend evaluates it, and evaluating a query is the
   * longer path: it may rewrite the pattern's terms before matching them, which is
   * where a backend can lose the connection between the guard and the write it is
   * meant to guard — precisely in the "is this brand-new resource already taken?"
   * case. This method states the pattern directly, so an implementation can answer it
   * from the backend's own pattern lookup. For the RDF4J backend the difference is
   * measured and the cause identified; see the implementation notes on
   * {@link DatasetTransactor} for what its isolation guarantee does and does not
   * cover.</p>
   *
   * @param namedGraph IRI identifying the named graph to search; must not be {@code null}
   * @param subject the subject to match, or {@code null} for any subject
   * @param predicate the predicate to match, or {@code null} for any predicate
   * @param object the object to match, or {@code null} for any object
   * @return {@code true} if the named graph contains at least one matching triple
   */
  boolean contains(IRI namedGraph, BlankNodeOrIRI subject, IRI predicate, RDFTerm object);

  /**
   * Executes a SPARQL ASK query within this transaction.
   *
   * @param sparql the SPARQL ASK query string; must not be {@code null} or empty
   * @return {@code true} if the pattern has at least one match
   * @throws MalformedSparqlException if the SPARQL string is syntactically invalid
   */
  boolean ask(String sparql);

  /**
   * Executes a SPARQL CONSTRUCT query and returns the resulting graph.
   *
   * <p>The graph contains the triples produced by the CONSTRUCT template and
   * pattern matching within the current transactional view of the dataset.</p>
   *
   * @param sparql the SPARQL CONSTRUCT query string; must not be {@code null} or empty
   * @return the constructed graph; never {@code null}
   * @throws MalformedSparqlException if the SPARQL string is syntactically invalid
   */
  ReadableGraph construct(String sparql);
}
