/**
 * Backend-neutral <em>hosting</em> port for a pool of datasets.
 *
 * <p>Where {@code rdf-dataset} defines what a consumer does <em>with</em> a
 * store (the content ports {@link io.kogn.rdf.dataset.GraphStore},
 * {@link io.kogn.rdf.dataset.SparqlQuery}, {@link io.kogn.rdf.dataset.SparqlUpdate},
 * {@link io.kogn.rdf.dataset.DatasetTransactor}), this package is about
 * <em>owning</em> a pool of them: an id-to-store registry with open-or-create,
 * eviction, deletion and lease-based in-flight protection.</p>
 *
 * <ul>
 *   <li>{@link io.kogn.rdf.dataset.hosting.DatasetLifecycle} — open-or-create/close/delete/list
 *       datasets, addressed by an opaque {@link io.kogn.rdf.dataset.hosting.DatasetId}, with
 *       lease-based in-flight protection;
 *       {@link io.kogn.rdf.dataset.hosting.DatasetLifecycle#acquire} returns a leased
 *       {@link io.kogn.rdf.dataset.hosting.DatasetHandle} exposing the content ports</li>
 *   <li>{@link io.kogn.rdf.dataset.hosting.DatasetHandle} — a leased, short-lived access handle
 *       to one dataset; <em>not</em> an RDF 1.1 dataset value, but a session that must be closed
 *       to release its lease</li>
 *   <li>{@link io.kogn.rdf.dataset.hosting.DatasetId} — the opaque dataset key</li>
 *   <li>{@link io.kogn.rdf.dataset.hosting.DatasetStoreConfig} — the backend-neutral store knobs
 *       (persistence, full-text-search requirement)</li>
 * </ul>
 *
 * <p>Hosting is a concern of whatever process owns the pool of stores, not an RDF concept: a
 * consumer that merely wires an adapter for a store this library does not host needs only the
 * content ports in {@code rdf-dataset} and does not depend on this package at all
 * (see ADR-0009).</p>
 *
 * <p>All interfaces are purely Java-based; no framework or library annotations are required
 * to implement them. Implementations live in adapter modules (e.g. {@code rdf-dataset-hosting-rdf4j}).</p>
 */
package io.kogn.rdf.dataset.hosting;
