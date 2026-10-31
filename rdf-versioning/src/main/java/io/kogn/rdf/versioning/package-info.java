/**
 * Versioning of RDF resources over a {@link io.kogn.rdf.dataset.DatasetTx}: a mutable
 * draft per proxy IRI, immutable versions named by their CID, and a history of
 * publishes (ADR-0021, ADR-0022, ADR-0023).
 *
 * <ul>
 *   <li>{@link io.kogn.rdf.versioning.ResourceVersioning} — the port</li>
 *   <li>{@link io.kogn.rdf.versioning.ResourceVersioningNamedGraphs} — its only
 *       implementation, plain logic over the dataset ports</li>
 *   <li>{@link io.kogn.rdf.versioning.VersioningGraphs} — the fixed IRIs of the
 *       library's named graphs</li>
 *   <li>{@link io.kogn.rdf.versioning.VocabVersioning} — the library's vocabulary</li>
 *   <li>{@link io.kogn.rdf.versioning.ContentGraphMismatchException} — a dataset used
 *       with a content graph other than the one it records</li>
 *   <li>{@link io.kogn.rdf.versioning.DraftSlice} and
 *       {@link io.kogn.rdf.versioning.RevisionToken} — a proxy IRI's draft slice and
 *       the opaque token for its next write</li>
 *   <li>{@link io.kogn.rdf.versioning.PublishResult} and
 *       {@link io.kogn.rdf.versioning.PublishDetails} — what a publish returns (the
 *       history entry and next token of each proxy IRI) and the optional creator and
 *       comment it records</li>
 *   <li>{@link io.kogn.rdf.versioning.HistoryEntry} and
 *       {@link io.kogn.rdf.versioning.PublishState} — what the reads of the history and
 *       of a draft's publish state return</li>
 *   <li>{@link io.kogn.rdf.versioning.RevisionConflictException} and
 *       {@link io.kogn.rdf.versioning.AmbiguousBlankNodeException} — a stale token; a
 *       blank node shared by two slices</li>
 * </ul>
 *
 * <h2>Backend-free</h2>
 *
 * <p>The module depends on {@code rdf-dataset} and {@code rdf-cid} only: no RDF4J, no
 * hosting layer. It runs on any {@code DatasetTx}, including one over a repository the
 * caller wraps itself, and port and implementation share the module, as in
 * {@code rdf-cid}.</p>
 */
package io.kogn.rdf.versioning;
