package io.kogn.rdf.dataset;

import java.util.stream.Stream;

import io.kogn.rdf.terms.IRI;
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
   * @throws IllegalArgumentException if the SPARQL string is syntactically invalid
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
   * @throws IllegalArgumentException if the SPARQL string is syntactically invalid
   */
  Stream<BindingSet> select(String sparql);

  /**
   * Executes a SPARQL ASK query within this transaction.
   *
   * @param sparql the SPARQL ASK query string; must not be {@code null} or empty
   * @return {@code true} if the pattern has at least one match
   * @throws IllegalArgumentException if the SPARQL string is syntactically invalid
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
   * @throws IllegalArgumentException if the SPARQL string is syntactically invalid
   */
  ReadableGraph construct(String sparql);
}
