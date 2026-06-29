/**
 * Named-graph-addressed dataset ports for RDF store access.
 *
 * <p>This package sits between the pure data-model layer ({@code cg-rdf-terms}) and the
 * higher-level content-addressed service layer ({@code cg-rdf-api}). It defines four
 * technology-neutral port interfaces that cover the basic operations a dataset consumer
 * needs:</p>
 *
 * <ul>
 *   <li>{@link io.kogn.rdf.dataset.GraphStore} — named-graph-addressed add/remove/clear/export</li>
 *   <li>{@link io.kogn.rdf.dataset.SparqlUpdate} — SPARQL UPDATE and ASK</li>
 *   <li>{@link io.kogn.rdf.dataset.DatasetTransactor} — atomic unit-of-work boundary</li>
 *   <li>{@link io.kogn.rdf.dataset.DatasetTx} — dataset operations within a transaction</li>
 * </ul>
 *
 * <p>All interfaces are purely Java-based; no framework or library annotations are required
 * to implement them. Implementations live in adapter modules (e.g. {@code cg-rdf-rdf4j}).</p>
 */
package io.kogn.rdf.dataset;
