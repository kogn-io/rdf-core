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
 * those interfaces for the documentation of the inherited operations. This interface
 * adds two pattern reads that have no non-transactional equivalent:
 * {@link #contains(IRI, BlankNodeOrIRI, IRI, RDFTerm) contains}, the guard read of
 * ADR-0008, and {@link #match(IRI, BlankNodeOrIRI, IRI, RDFTerm) match}, which returns the
 * matching triples and, unlike {@link #export(IRI) export}, observes only its pattern.</p>
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

  /**
   * Returns the triples of the named graph that match the given pattern, without going
   * through SPARQL.
   *
   * <p>Use {@code null} as a wildcard for any of subject, predicate and object, as in
   * {@link #contains(IRI, BlankNodeOrIRI, IRI, RDFTerm) contains}. {@code match(g, s, null,
   * null)} therefore reads everything {@code g} says about {@code s}; {@code match(g, null,
   * null, null)} reads the whole graph, like {@link #export(IRI) export}.</p>
   *
   * <p><strong>Observes only the pattern.</strong> Where the transaction's isolation
   * level tracks what a transaction read, this read counts as having read the pattern,
   * not the named graph: a concurrent commit of a triple outside the pattern does not make
   * this transaction lose its commit, a concurrent commit of a matching triple does. That
   * is the difference to {@link #export(IRI) export}, which observes the whole named graph
   * however little of it the caller goes on to use. A read that needs only some subjects
   * of a large, concurrently written graph should therefore state them as patterns here
   * rather than export the graph and filter. What the isolation level guarantees, and what
   * it does not for a guard against IRIs the store does not hold yet, is the
   * {@link DatasetTransactor}'s; a first-insert guard reads through {@code contains}
   * (ADR-0008).</p>
   *
   * <p>Like {@link #export(IRI) export}, the result is a snapshot taken when the call
   * returns: later writes in this transaction do not change it, and the caller may keep
   * it. A matching triple whose object is an RDF 1.2 triple term, which the data model
   * cannot represent but a SPARQL update can store, makes the call fail.</p>
   *
   * @param namedGraph IRI identifying the named graph to read; must not be {@code null}
   * @param subject the subject to match, or {@code null} for any subject
   * @param predicate the predicate to match, or {@code null} for any predicate
   * @param object the object to match, or {@code null} for any object
   * @return the matching triples, empty if there are none; never {@code null}
   * @throws DatasetStorageException if the backend fails while carrying out the read
   * @throws IllegalStateException if the transaction has already ended, or a matching
   *     triple holds an RDF 1.2 triple term
   */
  ReadableGraph match(IRI namedGraph, BlankNodeOrIRI subject, IRI predicate, RDFTerm object);
}
