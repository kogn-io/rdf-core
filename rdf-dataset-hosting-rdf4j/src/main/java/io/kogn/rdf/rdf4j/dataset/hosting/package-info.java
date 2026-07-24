/**
 * RDF4J-backed implementation of the {@code rdf-dataset-hosting} port.
 *
 * <p>{@link io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j} implements
 * {@link io.kogn.rdf.dataset.hosting.DatasetLifecycle}: it builds and owns the backing
 * RDF4J {@code Repository} for each dataset (a {@code MemoryStore} for {@code IN_MEMORY},
 * a {@code NativeStore} for {@code PERSISTENT}), enforces lease-based in-flight protection,
 * and hands out leased {@link io.kogn.rdf.dataset.hosting.DatasetHandle}s that never expose an
 * RDF4J type.</p>
 *
 * <p>The content ports behind each handle are the store-agnostic wrappers from
 * {@code rdf-dataset-rdf4j} ({@code GraphStoreRdf4j}, {@code SparqlQueryRdf4j},
 * {@code SparqlUpdateRdf4j}, {@code DatasetTransactorRdf4j}), composed over the
 * {@code Repository} this module constructs. Hosting is thus the only part of the RDF4J
 * backend that assembles a concrete store; the content adapters merely wrap a
 * caller-supplied one (see ADR-0009).</p>
 */
package io.kogn.rdf.rdf4j.dataset.hosting;
