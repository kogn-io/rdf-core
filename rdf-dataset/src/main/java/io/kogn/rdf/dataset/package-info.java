/**
 * Named-graph-addressed dataset ports for RDF store access.
 *
 * <p>This package sits above the pure data-model layer ({@code rdf-terms}) and defines
 * technology-neutral port interfaces that cover the basic operations a dataset consumer
 * needs:</p>
 *
 * <ul>
 *   <li>{@link io.kogn.rdf.dataset.GraphStore} — named-graph-addressed add/remove/clear/export</li>
 *   <li>{@link io.kogn.rdf.dataset.SparqlQuery} — SPARQL SELECT, CONSTRUCT and ASK (read)</li>
 *   <li>{@link io.kogn.rdf.dataset.SparqlUpdate} — SPARQL UPDATE (write)</li>
 *   <li>{@link io.kogn.rdf.dataset.DatasetTransactor} — atomic unit-of-work boundary</li>
 *   <li>{@link io.kogn.rdf.dataset.DatasetTx} — dataset operations within a transaction</li>
 *   <li>{@link io.kogn.rdf.dataset.DatasetLifecycle} — open-or-create/close/delete/list datasets,
 *       addressed by opaque {@link io.kogn.rdf.dataset.DatasetId}, with lease-based in-flight
 *       protection; {@link io.kogn.rdf.dataset.DatasetLifecycle#acquire} returns a leased
 *       {@link io.kogn.rdf.dataset.DatasetHandle} exposing the four ports above</li>
 * </ul>
 *
 * <h2>Data model: named graphs only — not an RDF 1.1 Dataset</h2>
 *
 * <p>A "dataset" here is a store of <em>named graphs</em>, nothing more. It is deliberately
 * <strong>not</strong> an
 * <a href="https://www.w3.org/TR/rdf11-concepts/#section-dataset">RDF 1.1 dataset</a>:</p>
 *
 * <ul>
 *   <li><strong>No default graph.</strong> The unnamed default graph of the RDF 1.1 model is
 *       not represented. It is intentionally left out (YAGNI) until a consumer needs it.</li>
 *   <li><strong>Graph names are IRIs only.</strong> A named graph is always addressed by an
 *       {@link io.kogn.rdf.terms.IRI}; blank-node graph names are not modelled.</li>
 *   <li><strong>Context-less reads are a union over all named graphs.</strong> A SPARQL read
 *       with no {@code GRAPH} clause (or {@code FROM}/{@code FROM NAMED}) ranges over the
 *       union of all named graphs, <em>not</em> over a default graph. There is no default
 *       graph to fall back to.</li>
 * </ul>
 *
 * <p>For the same reason {@link io.kogn.rdf.dataset.DatasetHandle} is named "handle", not
 * "dataset": it is a leased access object (≈ an RDF4J {@code RepositoryConnection}), not a
 * value-typed RDF dataset and not an {@code org.apache.commons.rdf.api.Dataset}.</p>
 *
 * <p>All interfaces are purely Java-based; no framework or library annotations are required
 * to implement them. Implementations live in adapter modules (e.g. {@code rdf-dataset-rdf4j}).</p>
 */
package io.kogn.rdf.dataset;
