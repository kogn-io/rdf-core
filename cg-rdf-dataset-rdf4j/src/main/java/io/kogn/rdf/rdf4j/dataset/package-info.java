/**
 * RDF4J implementations of the cg-rdf-dataset ports.
 *
 * <p>This package provides concrete RDF4J-backed implementations of the
 * dataset port interfaces defined in {@code cg-rdf-dataset}:</p>
 * <ul>
 *   <li>{@link io.kogn.rdf.rdf4j.dataset.GraphStoreRdf4j} - named-graph add/remove/clear/export</li>
 *   <li>{@link io.kogn.rdf.rdf4j.dataset.SparqlQueryRdf4j} - SPARQL SELECT, CONSTRUCT and ASK</li>
 *   <li>{@link io.kogn.rdf.rdf4j.dataset.SparqlUpdateRdf4j} - SPARQL UPDATE</li>
 *   <li>{@link io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j} - atomic unit-of-work</li>
 *   <li>{@link io.kogn.rdf.rdf4j.dataset.DatasetProviderRdf4j} - per-path service factory</li>
 * </ul>
 *
 * <p>{@code DatasetTxRdf4j} is package-private and only created by
 * {@link io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j} during a transaction.</p>
 *
 * <p>{@link io.kogn.rdf.rdf4j.dataset.Rdf4jRepositorySource} is the seam interface
 * that allows {@link io.kogn.rdf.rdf4j.dataset.DatasetProviderRdf4j} to share a
 * repository cache with a higher-level provider (e.g. {@code GraphServiceProviderRdf4j}
 * in {@code cg-rdf-rdf4j}) without creating a module cycle.</p>
 *
 * <p>All implementations operate on an RDF4J {@code Repository} but do not expose
 * RDF4J types in their public signatures — callers only see the port interfaces.</p>
 */
package io.kogn.rdf.rdf4j.dataset;
