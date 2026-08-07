// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset.hosting;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
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
import io.kogn.rdf.dataset.DatasetExport;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.RdfFormat;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig.Persistence;
import io.kogn.rdf.rdf4j.dataset.DatasetExportRdf4j;
import io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j;
import io.kogn.rdf.rdf4j.dataset.GraphStoreRdf4j;
import io.kogn.rdf.rdf4j.dataset.SparqlQueryRdf4j;
import io.kogn.rdf.rdf4j.dataset.SparqlUpdateRdf4j;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
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
 * <p>For {@code IN_MEMORY} datasets a {@link MemoryStore} holds the only copy of
 * the data, so {@link #close(DatasetId)} is destructive rather than a cheap,
 * resumable eviction: the store and its contents are gone, and the next
 * {@link #acquire(DatasetId)} builds a brand-new, empty one and re-runs the
 * on-create hook. See {@link DatasetLifecycle#close(DatasetId)} for the full
 * contract.</p>
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
 * <h2>A delete that fails</h2>
 *
 * <p>Removing a persistent dataset's storage is a directory walk, so it can fail
 * in the middle — a locked file, a permission problem — and leave remains behind.
 * Those remains must never be handed out: the directory is no longer empty, so the
 * store would be opened over whatever survived and the on-create hook would
 * <em>not</em> run, giving the caller a dataset that is neither the old one nor a
 * freshly seeded new one.</p>
 *
 * <p>{@link #delete(DatasetId)} therefore logs the failure at {@code ERROR} (in
 * addition to rethrowing it, which a caller may swallow) and marks the id as having
 * an unfinished delete — every failed delete marks it this way, regardless of how
 * far the on-disk teardown got, even one that failed on its very first file. The
 * mark itself is a {@code .deleting} file written inside the dataset's own
 * directory before the teardown starts and removed by it on success, so it survives
 * a process restart and a second {@link DatasetLifecycleRdf4j} instance over the
 * same {@code storageRoot} — not just this instance. Writing that file is
 * best-effort: if it fails too (most likely for the same reason the deletion is
 * about to fail), an in-process set is the fallback, upholding the guarantee for
 * the rest of this process's life but not across a restart.</p>
 *
 * <p>The next {@link #acquire(DatasetId)} first retries the cleanup: if it succeeds
 * the dataset is created afresh and seeded as usual, and if it fails again
 * {@code acquire} refuses the dataset with an {@link IllegalStateException} rather
 * than open the remains. The same applies to the rollback of a failed creation.
 * Until the remains are gone {@link #list()} keeps reporting the id, because its
 * directory is still there.</p>
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

  /**
   * Name of the on-disk marker file written inside a dataset's directory while its deletion is
   * unfinished — see the class documentation.
   */
  private static final String DELETION_MARKER_FILE_NAME = ".deleting";

  private final DatasetStoreConfig config;
  private final Path storageRoot;
  private final String indexSpec;
  private final BiConsumer<DatasetId, GraphStore> onCreate;

  private final ConcurrentHashMap<DatasetId, ManagedDataset> datasets = new ConcurrentHashMap<>();

  /**
   * In-process fallback for ids whose delete() is unfinished, used when writing the on-disk
   * {@value #DELETION_MARKER_FILE_NAME} marker itself fails. The marker file is the source of
   * truth that survives a process restart and a second instance over the same
   * {@code storageRoot}; this set only covers the rest of this process's life — see the class
   * documentation.
   */
  private final Set<DatasetId> deletionUnfinished = ConcurrentHashMap.newKeySet();

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
   * @param onCreate hook run when a dataset's store is first created, before its
   *     handle is handed out; receives the id and a {@link GraphStore} to seed
   *     through (never an RDF4J type). One-time per store, not per {@link DatasetId}:
   *     for {@code IN_MEMORY} a {@link #close(DatasetId)} destroys the store, so the
   *     next {@link #acquire(DatasetId)} runs the hook again for the same id. It runs
   *     under the per-key map lock, so it must only seed its own
   *     {@code GraphStore} and must not call back into this
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

  /**
   * {@inheritDoc}
   *
   * <p>If an earlier {@link #delete(DatasetId)} left an unfinished on-disk teardown, this first
   * retries that cleanup and throws {@link IllegalStateException} if the remains are still there
   * — see the class documentation.</p>
   *
   * @throws IllegalStateException if a failed delete left remains that could not be cleaned up
   */
  @Override
  public DatasetHandle acquire(final DatasetId id) {
    Objects.requireNonNull(id, "id");
    final ManagedDataset managed = datasets.compute(id, (key, existing) -> {
      requireNoRemainsOfFailedDelete(key);
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

  /**
   * {@inheritDoc}
   *
   * <p>A failure of the on-disk teardown is logged at {@code ERROR} as well as rethrown, and
   * marks the dataset as having an unfinished delete so the next {@link #acquire(DatasetId)}
   * does not open its remains — see the class documentation.</p>
   */
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
          deleteStorageOrMarkPartial(key);
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
        try {
          deleteStorageOrMarkPartial(id);
        } catch (final RuntimeException rollbackFailure) {
          // the rollback itself failed, so the invariant cannot be restored here: the mark keeps
          // the remains from being handed out. Report the failure that started this, not the one
          // from cleaning up after it.
          e.addSuppressed(rollbackFailure);
        }
      }
      throw e;
    }
  }

  /**
   * Deletes a dataset's storage, marking the id on any failure — via the on-disk
   * {@value #DELETION_MARKER_FILE_NAME} file and, as a fallback, {@link #deletionUnfinished} —
   * regardless of how far the teardown got: {@link #acquire(DatasetId)} must not open the
   * directory as though it were an intact dataset.
   *
   * <p>The failure is logged at {@code ERROR} because rethrowing alone is not enough to make it
   * findable in operation — a caller that swallows the exception would otherwise leave no trace
   * that a dataset is now in a broken intermediate state.</p>
   */
  private void deleteStorageOrMarkPartial(final DatasetId id) {
    final Path dir = resolveDir(id).toPath();
    if (Files.isDirectory(dir)) {
      // written before the teardown starts (not inside deleteStorageOnDisk, which a test in this
      // package overrides wholesale) so the mark is unconditional; the reverse-order walk in
      // deleteStorageOnDisk removes it again along with the rest of the directory on success.
      markDeletionInProgress(id, dir);
    }
    try {
      deleteStorageOnDisk(id);
    } catch (final RuntimeException e) {
      deletionUnfinished.add(id);
      log.error(
          "Deleting the storage of dataset {} failed; the identifier is marked as having an unfinished"
              + " delete, regardless of how far the on-disk teardown got. It cannot be acquired until the remains"
              + " are gone — the next acquire() retries the cleanup and fails if it cannot complete it.",
          id.value(), e);
      throw e;
    }
    deletionUnfinished.remove(id);
  }

  /**
   * Writes the {@value #DELETION_MARKER_FILE_NAME} marker inside {@code dir}, ignoring it if
   * already present (a retry of an earlier failed attempt).
   *
   * <p>Best-effort: if writing it fails — most likely for the same reason the deletion that is
   * about to run will also fail, e.g. a permission problem in the directory — the in-process
   * {@link #deletionUnfinished} set remains as the fallback; see the class documentation.</p>
   */
  private void markDeletionInProgress(final DatasetId id, final Path dir) {
    try {
      Files.createFile(dir.resolve(DELETION_MARKER_FILE_NAME));
    } catch (final FileAlreadyExistsException e) {
      // already marked by an earlier failed attempt — fine, this is a retry.
    } catch (final IOException e) {
      log.warn("Could not write the deletion marker for dataset {}; falling back to in-process tracking, which"
          + " does not survive a process restart.", id.value(), e);
    }
  }

  /**
   * Refuses to hand {@code id} out while a previous {@link #delete(DatasetId)} left it with an
   * unfinished on-disk teardown — retrying the cleanup first, so that no caller is ever handed
   * the remains.
   *
   * <p>Retrying the cleanup is the outcome to prefer: it leaves an empty directory, which
   * {@link #isNewStore(File)} reads as "new dataset", so the store is created afresh and the
   * on-create hook runs. Cleaning up is not always available, though — the deletion may fail
   * again for the very reason it failed the first time — so refusing the dataset is the fallback
   * that always holds.</p>
   */
  private void requireNoRemainsOfFailedDelete(final DatasetId id) {
    if (!hasRemainsOfFailedDelete(id)) {
      return;
    }
    try {
      deleteStorageOrMarkPartial(id);
    } catch (final RuntimeException e) {
      throw new IllegalStateException("dataset '" + id.value() + "' has an unfinished delete() and the remains"
          + " could not be cleaned up; it cannot be acquired while they are there", e);
    }
  }

  /**
   * Whether {@code id} was left with an unfinished delete — the on-disk
   * {@value #DELETION_MARKER_FILE_NAME} marker is authoritative and survives a process restart
   * and a second instance over the same {@code storageRoot}; {@link #deletionUnfinished} is
   * consulted too, as the fallback for when writing that marker itself failed. {@code IN_MEMORY}
   * has no storage and is never marked either way.
   */
  private boolean hasRemainsOfFailedDelete(final DatasetId id) {
    if (config.persistence() == Persistence.IN_MEMORY) {
      return false;
    }
    if (deletionUnfinished.contains(id)) {
      return true;
    }
    return Files.exists(resolveDir(id).toPath().resolve(DELETION_MARKER_FILE_NAME));
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
   *
   * <p>{@code Files.walk} lists {@code dir}'s current contents, so this also deletes a
   * {@value #DELETION_MARKER_FILE_NAME} marker written into it beforehand: on the reverse-order
   * walk below, a proper prefix of a path sorts before it, so the parent directory is always
   * deleted last, after every entry inside it — the marker included, whatever position among its
   * siblings it happens to sort into.</p>
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

  /** A cached, leasable dataset: its store and the five port wrappers over it. */
  private static final class ManagedDataset {

    private final Repository repository;
    private final GraphStore graphStore;
    private final SparqlQuery sparqlQuery;
    private final SparqlUpdate sparqlUpdate;
    private final DatasetExport datasetExport;
    private final DatasetTransactor transactor;
    private final AtomicInteger leaseCount = new AtomicInteger();

    private ManagedDataset(final Repository repository) {
      this.repository = repository;
      this.graphStore = new GraphStoreRdf4j(repository);
      this.sparqlQuery = new SparqlQueryRdf4j(repository);
      this.sparqlUpdate = new SparqlUpdateRdf4j(repository);
      this.datasetExport = new DatasetExportRdf4j(repository);
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
    private final DatasetExport datasetExport;
    private final DatasetTransactor transactor;

    private LeasedDatasetHandle(final ManagedDataset managed) {
      this.managed = managed;
      this.graphStore = new HandleBoundGraphStore(managed.graphStore, closed);
      this.sparqlQuery = new HandleBoundSparqlQuery(managed.sparqlQuery, closed);
      this.sparqlUpdate = new HandleBoundSparqlUpdate(managed.sparqlUpdate, closed);
      this.datasetExport = new HandleBoundDatasetExport(managed.datasetExport, closed);
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
    public DatasetExport datasetExport() {
      return datasetExport;
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
    public Stream<BindingSet> select(final String sparql, final Map<String, RDFTerm> bindings) {
      ensureOpen(closed);
      return delegate.select(sparql, bindings);
    }

    @Override
    public ReadableGraph construct(final String sparql) {
      ensureOpen(closed);
      return delegate.construct(sparql);
    }

    @Override
    public ReadableGraph construct(final String sparql, final Map<String, RDFTerm> bindings) {
      ensureOpen(closed);
      return delegate.construct(sparql, bindings);
    }

    @Override
    public boolean ask(final String sparql) {
      ensureOpen(closed);
      return delegate.ask(sparql);
    }

    @Override
    public boolean ask(final String sparql, final Map<String, RDFTerm> bindings) {
      ensureOpen(closed);
      return delegate.ask(sparql, bindings);
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

    @Override
    public void update(final String sparql, final Map<String, RDFTerm> bindings) {
      ensureOpen(closed);
      delegate.update(sparql, bindings);
    }
  }

  /**
   * Thin, per-handle {@link DatasetExport} delegate that checks the owning handle's
   * {@code closed} flag before every call.
   *
   * <p>The check is made when the call starts, as in every wrapper here. That matters more
   * for an export than for the others: a dump streams at the pace of the caller's sink, so a
   * handle closed while one is running does not stop it — it only releases the lease that
   * was keeping the store from being evicted or deleted underneath it.</p>
   */
  private static final class HandleBoundDatasetExport implements DatasetExport {

    private final DatasetExport delegate;
    private final AtomicBoolean closed;

    private HandleBoundDatasetExport(final DatasetExport delegate, final AtomicBoolean closed) {
      this.delegate = delegate;
      this.closed = closed;
    }

    @Override
    public void export(final OutputStream out, final RdfFormat format) {
      ensureOpen(closed);
      delegate.export(out, format);
    }

    @Override
    public void export(final OutputStream out, final RdfFormat format, final IRI namedGraph) {
      ensureOpen(closed);
      delegate.export(out, format, namedGraph);
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
