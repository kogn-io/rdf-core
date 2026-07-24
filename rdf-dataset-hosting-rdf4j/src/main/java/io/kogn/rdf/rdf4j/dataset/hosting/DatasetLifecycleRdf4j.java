// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset.hosting;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig.Persistence;
import io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j;
import io.kogn.rdf.rdf4j.dataset.GraphStoreRdf4j;
import io.kogn.rdf.rdf4j.dataset.SparqlQueryRdf4j;
import io.kogn.rdf.rdf4j.dataset.SparqlUpdateRdf4j;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;
import lombok.extern.slf4j.Slf4j;

/**
 * RDF4J-backed {@link DatasetLifecycle}.
 *
 * <p>Builds the backing store from a {@link DatasetStoreConfig}
 * ({@link MemoryStore} for {@code IN_MEMORY}, {@link NativeStore} for
 * {@code PERSISTENT}, default index spec {@code "spoc,posc,cosp"}) and never
 * exposes the RDF4J {@code Repository} — callers only ever see the neutral port
 * types via a leased {@link DatasetHandle}.</p>
 *
 * <h2>One instance per storage location</h2>
 *
 * <p>An instance <strong>owns its {@code storageRoot} exclusively</strong>. Each
 * dataset's store is cached in this instance and held open, and RDF4J's
 * {@link NativeStore} locks its directory. Two lifecycles over the same
 * {@code storageRoot} therefore do not share the physical store: the second one
 * fails with RDF4J's {@code RepositoryLockedException} as soon as it touches the
 * same {@link DatasetId}.</p>
 *
 * <p>So construct <strong>one</strong> lifecycle per storage location and share it
 * across every logical repository that reads or writes there — a single injected
 * bean, not one instance per consuming component. Sharing the instance is also what
 * makes cross-repository reads within one dataset work at all, since only then do
 * the readers see the same store. The lock is held by the operating-system process,
 * so a second JVM over the same directory fails the same way; no in-process
 * arrangement can avoid that.</p>
 *
 * <h2>In-flight protection</h2>
 *
 * <p>Each dataset is held in a {@link ManagedDataset} carrying a lease count.
 * {@link #acquire(DatasetId)} takes its lease inside the same
 * {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)} call
 * that creates-or-finds the dataset, so the count is incremented under the
 * per-key lock before the handle is handed out. {@link #close(DatasetId)} and
 * {@link #delete(DatasetId)} run under the same per-key lock and inspect the
 * lease count, so a store can never be shut down or deleted while a handle is
 * open — this closes the time-of-check-to-time-of-use race that a bare
 * get-or-create + evict design suffers from.</p>
 *
 * <h2>Path safety</h2>
 *
 * <p>The opaque {@link DatasetId} value is never used as a path. It is
 * Base64url-encoded into a single directory segment, so values such as
 * {@code "../etc"} cannot escape the storage root.</p>
 *
 * <p>Store creation runs under the per-key lock; for the expected workload
 * (few datasets, rare creation) holding the lock across store initialisation is
 * an acceptable trade for correctness.</p>
 */
@Slf4j
public class DatasetLifecycleRdf4j implements DatasetLifecycle {

  /** Default RDF4J NativeStore triple-index specification. */
  public static final String DEFAULT_INDEX_SPEC = "spoc,posc,cosp";

  private final DatasetStoreConfig config;
  private final Path storageRoot;
  private final String indexSpec;
  private final BiConsumer<DatasetId, GraphStore> onCreate;

  private final ConcurrentHashMap<DatasetId, ManagedDataset> datasets = new ConcurrentHashMap<>();

  /**
   * Creates a lifecycle.
   *
   * @param config backend-neutral store configuration; must not be {@code null}.
   *     {@code fullTextSearch == true} is rejected until full-text-search store
   *     assembly is implemented
   * @param storageRoot root directory under which persistent datasets live;
   *     required (non-{@code null}) for {@code PERSISTENT}, ignored for
   *     {@code IN_MEMORY}
   * @param indexSpec RDF4J NativeStore index specification; required for
   *     {@code PERSISTENT} (e.g. {@link #DEFAULT_INDEX_SPEC})
   * @param onCreate one-time hook run when a dataset is first created, before its
   *     handle is handed out; receives the id and a {@link GraphStore} to seed
   *     through (never an RDF4J type). It runs under the per-key map lock, so it
   *     must only seed its own {@code GraphStore} and must not call back into this
   *     lifecycle ({@code acquire}/{@code close}/{@code delete}/{@code list}). If it
   *     throws, creation is rolled back (store shut down, a newly created persistent
   *     store removed) and the exception propagates from {@code acquire}. May be
   *     {@code null}
   * @throws UnsupportedOperationException if {@code config.fullTextSearch()} is
   *     {@code true}
   */
  public DatasetLifecycleRdf4j(final DatasetStoreConfig config, final Path storageRoot, final String indexSpec,
      final BiConsumer<DatasetId, GraphStore> onCreate) {
    this.config = Objects.requireNonNull(config, "config");
    if (config.fullTextSearch()) {
      throw new UnsupportedOperationException("FTS store assembly lands with #6");
    }
    if (config.persistence() == Persistence.PERSISTENT) {
      this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot is required for PERSISTENT").normalize();
      this.indexSpec = Objects.requireNonNull(indexSpec, "indexSpec is required for PERSISTENT");
    } else {
      this.storageRoot = storageRoot == null ? null : storageRoot.normalize();
      this.indexSpec = indexSpec;
    }
    this.onCreate = onCreate;
  }

  /**
   * Convenience constructor using {@link #DEFAULT_INDEX_SPEC} and no on-create
   * hook.
   *
   * @param config backend-neutral store configuration
   * @param storageRoot root directory for persistent datasets
   */
  public DatasetLifecycleRdf4j(final DatasetStoreConfig config, final Path storageRoot) {
    this(config, storageRoot, DEFAULT_INDEX_SPEC, null);
  }

  @Override
  public DatasetHandle acquire(final DatasetId id) {
    Objects.requireNonNull(id, "id");
    final ManagedDataset managed = datasets.compute(id, (key, existing) -> {
      final ManagedDataset md = existing != null ? existing : createAndSeed(key);
      md.leaseCount.incrementAndGet();
      return md;
    });
    return new LeasedDatasetHandle(managed);
  }

  @Override
  public void close(final DatasetId id) {
    Objects.requireNonNull(id, "id");
    final RuntimeException[] teardownFailure = new RuntimeException[1];
    datasets.compute(id, (key, md) -> {
      if (md == null) {
        return null;
      }
      if (md.leaseCount.get() > 0) {
        return md; // in use — eviction is a no-op; policy retries later
      }
      try {
        shutDownQuietly(md.repository);
        log.debug("Closed dataset {}", key.value());
      } catch (final RuntimeException e) {
        // shutDown() is not exception-free; the store may now be half torn-down and unusable, so
        // the cache must not keep serving it to the next acquire() — drop the entry regardless and
        // let the real failure surface, instead of leaking a dead store under a live-looking key.
        teardownFailure[0] = e;
      }
      return null;
    });
    if (teardownFailure[0] != null) {
      throw teardownFailure[0];
    }
  }

  @Override
  public void delete(final DatasetId id) {
    Objects.requireNonNull(id, "id");
    final RuntimeException[] teardownFailure = new RuntimeException[1];
    datasets.compute(id, (key, md) -> {
      if (md != null && md.leaseCount.get() > 0) {
        throw new IllegalStateException("cannot delete dataset '" + key.value() + "' with open leases");
      }
      try {
        if (md != null) {
          shutDownQuietly(md.repository);
        }
        if (config.persistence() != Persistence.IN_MEMORY) {
          deleteStorageOnDisk(key);
        }
        log.debug("Deleted dataset {}", key.value());
      } catch (final RuntimeException e) {
        // the repository may already be shut down while storage deletion failed (locked file,
        // permission problem): the store is no longer usable either way, so the cache must not
        // keep the mapping — drop it regardless and let the real teardown failure surface.
        teardownFailure[0] = e;
      }
      return null;
    });
    if (teardownFailure[0] != null) {
      throw teardownFailure[0];
    }
  }

  @Override
  public Set<DatasetId> list() {
    final Set<DatasetId> result = new HashSet<>(datasets.keySet());
    if (config.persistence() != Persistence.IN_MEMORY && storageRoot != null && Files.isDirectory(storageRoot)) {
      try (Stream<Path> entries = Files.list(storageRoot)) {
        entries.filter(Files::isDirectory)
            .map(p -> decodeSegment(p.getFileName().toString()))
            .filter(Objects::nonNull)
            .forEach(result::add);
      } catch (final IOException e) {
        throw new UncheckedIOException("failed to list datasets under " + storageRoot, e);
      }
    }
    return result;
  }

  /**
   * Shuts every open dataset down without deleting any storage. Intended for
   * orderly shutdown (e.g. {@code @PreDestroy} / test tear-down).
   *
   * <p><strong>Last resort — does not honour open leases.</strong> Unlike
   * {@link #close(DatasetId)} and {@link #delete(DatasetId)}, this method does not
   * consult {@code leaseCount} or take the per-key lock: it tears every store down
   * unconditionally, including ones with an open {@link DatasetHandle}. Any handle
   * still open at that point keeps its {@code closed} flag {@code false} — its
   * accessors do not yet throw — but operating on the now-shut-down store fails
   * with whatever the backend raises for a closed repository. Call this only when
   * the process is going down anyway (e.g. {@code @PreDestroy} or test tear-down),
   * never as a substitute for releasing leases in the normal course of business. If
   * any dataset still has an open lease, a warning is logged naming it before
   * teardown proceeds.</p>
   */
  public void shutDownAll() {
    final Set<DatasetId> stillLeased = datasets.entrySet()
        .stream()
        .filter(entry -> entry.getValue().leaseCount.get() > 0)
        .map(Entry::getKey)
        .collect(Collectors.toSet());
    if (!stillLeased.isEmpty()) {
      log.warn("shutDownAll: tearing down {} dataset(s) with an open lease, ignoring in-flight protection: {}",
          stillLeased.size(), stillLeased);
    }
    datasets.values().forEach(md -> shutDownQuietly(md.repository));
    datasets.clear();
  }

  // ---------------------------------------------------------------------------

  private ManagedDataset createAndSeed(final DatasetId id) {
    final boolean isNew;
    final Repository repository;
    if (config.persistence() == Persistence.IN_MEMORY) {
      repository = new SailRepository(new MemoryStore());
      isNew = true;
    } else {
      final File dir = resolveDir(id);
      isNew = isNewStore(dir);
      repository = new SailRepository(new NativeStore(dir, indexSpec));
    }
    try {
      repository.init();
      final ManagedDataset managed = new ManagedDataset(repository);
      if (isNew && onCreate != null) {
        onCreate.accept(id, managed.graphStore);
      }
      return managed;
    } catch (final RuntimeException e) {
      // init or the on-create seed failed: don't leak the (possibly) initialised store, and
      // don't leave a half-created persistent store on disk — that would make isNewStore false
      // on the next acquire, so onCreate would never run again and the dataset would stay
      // unseeded. Restore the invariant: a dataset is created-and-seeded atomically, or not at all.
      shutDownQuietly(repository);
      if (isNew && config.persistence() != Persistence.IN_MEMORY) {
        deleteStorageOnDisk(id);
      }
      throw e;
    }
  }

  private static boolean isNewStore(final File dir) {
    final String[] entries = dir.list();
    return entries == null || entries.length == 0;
  }

  /**
   * Maps an opaque dataset id to a single, traversal-safe directory under the
   * storage root by Base64url-encoding its value.
   */
  private File resolveDir(final DatasetId id) {
    final String segment = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(id.value().getBytes(StandardCharsets.UTF_8));
    final Path dir = storageRoot.resolve(segment).normalize();
    if (!dir.startsWith(storageRoot)) {
      // unreachable for a Base64url segment; defends the invariant if encoding changes
      throw new IllegalArgumentException("datasetId maps outside storage root: " + id.value());
    }
    return dir.toFile();
  }

  private DatasetId decodeSegment(final String segment) {
    try {
      final byte[] decoded = Base64.getUrlDecoder().decode(segment);
      final String reEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
      if (!reEncoded.equals(segment)) {
        return null; // not the canonical encoding resolveDir produces — foreign directory, skip
      }
      return new DatasetId(new String(decoded, StandardCharsets.UTF_8));
    } catch (final IllegalArgumentException e) {
      return null; // foreign directory not produced by this lifecycle — skip
    }
  }

  /**
   * Package-private (not {@code private}) so a test in this package can override it to force a
   * deterministic on-disk teardown failure — see {@code DatasetLifecycleRdf4jTest}.
   */
  void deleteStorageOnDisk(final DatasetId id) {
    final Path dir = resolveDir(id).toPath();
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.delete(path);
        } catch (final IOException e) {
          throw new UncheckedIOException("failed to delete " + path, e);
        }
      });
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to delete storage for " + id.value(), e);
    }
  }

  /**
   * Package-private (not {@code private}) so a test in this package can override it to force a
   * deterministic repository-teardown failure — see {@code DatasetLifecycleRdf4jTest}.
   */
  void shutDownQuietly(final Repository repository) {
    if (repository.isInitialized()) {
      repository.shutDown();
    }
  }

  // ---------------------------------------------------------------------------

  /** A cached, leasable dataset: its store and the four port wrappers over it. */
  private static final class ManagedDataset {

    private final Repository repository;
    private final GraphStore graphStore;
    private final SparqlQuery sparqlQuery;
    private final SparqlUpdate sparqlUpdate;
    private final DatasetTransactor transactor;
    private final AtomicInteger leaseCount = new AtomicInteger();

    private ManagedDataset(final Repository repository) {
      this.repository = repository;
      this.graphStore = new GraphStoreRdf4j(repository);
      this.sparqlQuery = new SparqlQueryRdf4j(repository);
      this.sparqlUpdate = new SparqlUpdateRdf4j(repository);
      this.transactor = new DatasetTransactorRdf4j(repository);
    }
  }

  /** Lease handle: releases its lease exactly once on {@link #close()}. */
  private static final class LeasedDatasetHandle implements DatasetHandle {

    private final ManagedDataset managed;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final GraphStore graphStore;
    private final SparqlQuery sparqlQuery;
    private final SparqlUpdate sparqlUpdate;
    private final DatasetTransactor transactor;

    private LeasedDatasetHandle(final ManagedDataset managed) {
      this.managed = managed;
      this.graphStore = new HandleBoundGraphStore(managed.graphStore, closed);
      this.sparqlQuery = new HandleBoundSparqlQuery(managed.sparqlQuery, closed);
      this.sparqlUpdate = new HandleBoundSparqlUpdate(managed.sparqlUpdate, closed);
      this.transactor = new HandleBoundDatasetTransactor(managed.transactor, closed);
    }

    @Override
    public GraphStore graphStore() {
      return graphStore;
    }

    @Override
    public SparqlQuery sparqlQuery() {
      return sparqlQuery;
    }

    @Override
    public SparqlUpdate sparqlUpdate() {
      return sparqlUpdate;
    }

    @Override
    public DatasetTransactor transactor() {
      return transactor;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        managed.leaseCount.decrementAndGet();
      }
    }
  }

  /**
   * Throws {@link IllegalStateException} if {@code closed} has already been set,
   * enforcing the "do not retain beyond the handle" rule stated on
   * {@link DatasetHandle}.
   */
  private static void ensureOpen(final AtomicBoolean closed) {
    if (closed.get()) {
      throw new IllegalStateException("handle is closed");
    }
  }

  /**
   * Thin, per-handle {@link GraphStore} delegate that checks the owning handle's
   * {@code closed} flag before every call.
   */
  private static final class HandleBoundGraphStore implements GraphStore {

    private final GraphStore delegate;
    private final AtomicBoolean closed;

    private HandleBoundGraphStore(final GraphStore delegate, final AtomicBoolean closed) {
      this.delegate = delegate;
      this.closed = closed;
    }

    @Override
    public long add(final IRI namedGraph, final ReadableGraph triples) {
      ensureOpen(closed);
      return delegate.add(namedGraph, triples);
    }

    @Override
    public long remove(final IRI namedGraph, final ReadableGraph triples) {
      ensureOpen(closed);
      return delegate.remove(namedGraph, triples);
    }

    @Override
    public void clear(final IRI namedGraph) {
      ensureOpen(closed);
      delegate.clear(namedGraph);
    }

    @Override
    public ReadableGraph export(final IRI namedGraph) {
      ensureOpen(closed);
      return delegate.export(namedGraph);
    }

    @Override
    public long count(final IRI namedGraph) {
      ensureOpen(closed);
      return delegate.count(namedGraph);
    }

    @Override
    public long count() {
      ensureOpen(closed);
      return delegate.count();
    }
  }

  /**
   * Thin, per-handle {@link SparqlQuery} delegate that checks the owning handle's
   * {@code closed} flag before every call.
   */
  private static final class HandleBoundSparqlQuery implements SparqlQuery {

    private final SparqlQuery delegate;
    private final AtomicBoolean closed;

    private HandleBoundSparqlQuery(final SparqlQuery delegate, final AtomicBoolean closed) {
      this.delegate = delegate;
      this.closed = closed;
    }

    @Override
    public Stream<BindingSet> select(final String sparql) {
      ensureOpen(closed);
      return delegate.select(sparql);
    }

    @Override
    public ReadableGraph construct(final String sparql) {
      ensureOpen(closed);
      return delegate.construct(sparql);
    }

    @Override
    public boolean ask(final String sparql) {
      ensureOpen(closed);
      return delegate.ask(sparql);
    }
  }

  /**
   * Thin, per-handle {@link SparqlUpdate} delegate that checks the owning handle's
   * {@code closed} flag before every call.
   */
  private static final class HandleBoundSparqlUpdate implements SparqlUpdate {

    private final SparqlUpdate delegate;
    private final AtomicBoolean closed;

    private HandleBoundSparqlUpdate(final SparqlUpdate delegate, final AtomicBoolean closed) {
      this.delegate = delegate;
      this.closed = closed;
    }

    @Override
    public void update(final String sparql) {
      ensureOpen(closed);
      delegate.update(sparql);
    }
  }

  /**
   * Thin, per-handle {@link DatasetTransactor} delegate that checks the owning
   * handle's {@code closed} flag before every call.
   */
  private static final class HandleBoundDatasetTransactor implements DatasetTransactor {

    private final DatasetTransactor delegate;
    private final AtomicBoolean closed;

    private HandleBoundDatasetTransactor(final DatasetTransactor delegate, final AtomicBoolean closed) {
      this.delegate = delegate;
      this.closed = closed;
    }

    @Override
    public <T> T inTransaction(final Function<DatasetTx, T> work) {
      ensureOpen(closed);
      return delegate.inTransaction(work);
    }
  }
}
