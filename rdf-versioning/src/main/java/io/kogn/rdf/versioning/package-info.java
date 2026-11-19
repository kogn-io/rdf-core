/**
 * Versioning of RDF resources over a {@link io.kogn.rdf.dataset.DatasetTx}: a mutable
 * draft per proxy IRI, immutable versions named by their CID, and a history of
 * publishes and tombstones (ADR-0021, ADR-0022, ADR-0023).
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
 *   <li>{@link io.kogn.rdf.versioning.PublishResult},
 *       {@link io.kogn.rdf.versioning.EntryResult} and
 *       {@link io.kogn.rdf.versioning.EntryDetails} — what a publish returns (per proxy
 *       IRI the history entry and next token) and the optional creator and comment a
 *       publish or delete records</li>
 *   <li>{@link io.kogn.rdf.versioning.DeleteResult} — what a delete returns (the
 *       tombstone entry and the next token)</li>
 *   <li>{@link io.kogn.rdf.versioning.HistoryEntry},
 *       {@link io.kogn.rdf.versioning.EntryKind} and
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
 *
 * <h2>Blank-node labels</h2>
 *
 * <p>The module assumes, and {@code DatasetTx} does not promise, that within one transaction a
 * blank node read from the store keeps its label: a slice's blank nodes are read as subject and
 * as object, and removed again, by the labels an earlier read of the same transaction returned,
 * and distinct nodes must keep distinct labels. Nothing is assumed across transactions; every
 * operation reads the slice anew. A second backend has to keep that within a transaction
 * (ADR-0024).</p>
 */
package io.kogn.rdf.versioning;
