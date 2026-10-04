// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;

/**
 * Dataset operations available within a {@link DatasetTransactor} transaction.
 *
 * <p>Composes {@link GraphStore}, {@link SparqlQuery} and {@link SparqlUpdate}
 * (ADR-0011): every operation those three ports declare participates in the
 * surrounding atomic unit-of-work when called through a {@code DatasetTx}, instead of
 * each being its own implicit, single-operation transaction the way it is when one of
 * those ports is used directly. Implementations must guarantee that either all
 * operations are committed together or none are (full rollback on any exception). See
 * those interfaces for the documentation of the inherited operations; this interface
 * adds only {@link #contains(IRI, BlankNodeOrIRI, IRI, RDFTerm) contains}, which has no
 * non-transactional equivalent (ADR-0008).</p>
 *
 * <p>Instances are created and managed by {@link DatasetTransactor#inTransaction}
 * and must not be used outside the scope of that call. An instance kept past the end of its
 * transaction — committed or rolled back — is dead: every operation, the SPARQL ones as well as
 * the graph ones, fails with an {@link IllegalStateException}. This is a programming error, not a
 * failure of the store, so it is deliberately neither a {@link SparqlEvaluationException} nor a
 * {@link DatasetStorageException}: a caller that retries on those types must not retry it.</p>
 */
public interface DatasetTx extends GraphStore, SparqlQuery, SparqlUpdate {

  /**
   * Checks whether the named graph contains a triple matching the given pattern,
   * without going through SPARQL.
   *
   * <p>Use {@code null} as a wildcard for any of subject, predicate and object,
   * following {@link ReadableGraph#stream(BlankNodeOrIRI, IRI, RDFTerm)}. {@code
   * contains(g, s, p, null)} therefore asks "does {@code s} already have any value for
   * {@code p} in {@code g}?".</p>
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
   * <p>Unlike the inherited {@link #export(IRI) export}, {@link #select select} and
   * {@link #construct construct}, which fail on an RDF 1.2 triple term (see
   * {@link GraphStore} and {@link SparqlQuery}), this method returns only a boolean and is
   * unaffected by one.</p>
   *
   * @param namedGraph IRI identifying the named graph to search; must not be {@code null}
   * @param subject the subject to match, or {@code null} for any subject
   * @param predicate the predicate to match, or {@code null} for any predicate
   * @param object the object to match, or {@code null} for any object
   * @return {@code true} if the named graph contains at least one matching triple
   * @throws DatasetStorageException if the backend fails while carrying out the lookup
   * @throws IllegalStateException if the transaction has already ended
   */
  boolean contains(IRI namedGraph, BlankNodeOrIRI subject, IRI predicate, RDFTerm object);
}
