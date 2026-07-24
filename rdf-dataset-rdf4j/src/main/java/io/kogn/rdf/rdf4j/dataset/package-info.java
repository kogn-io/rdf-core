/**
 * RDF4J implementations of the rdf-dataset ports.
 *
 * <p>This package provides concrete RDF4J-backed implementations of the
 * dataset port interfaces defined in {@code rdf-dataset}:</p>
 * <ul>
 *   <li>{@link io.kogn.rdf.rdf4j.dataset.GraphStoreRdf4j} - named-graph add/remove/clear/export</li>
 *   <li>{@link io.kogn.rdf.rdf4j.dataset.SparqlQueryRdf4j} - SPARQL SELECT, CONSTRUCT and ASK</li>
 *   <li>{@link io.kogn.rdf.rdf4j.dataset.SparqlUpdateRdf4j} - SPARQL UPDATE</li>
 *   <li>{@link io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j} - atomic unit-of-work</li>
 * </ul>
 *
 * <p>{@code DatasetTxRdf4j} is package-private and only created by
 * {@link io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j} during a transaction.</p>
 *
 * <p>Each implementation is a store-agnostic wrapper over a caller-supplied RDF4J
 * {@code Repository} and does not expose RDF4J types in its public signatures — callers
 * only see the port interfaces. These wrappers do not build a store; assembling and owning a
 * {@code Repository} is the job of the hosting adapter ({@code rdf-dataset-hosting-rdf4j}),
 * which composes these same wrappers behind a leased handle (see ADR-0009).</p>
 */
package io.kogn.rdf.rdf4j.dataset;
