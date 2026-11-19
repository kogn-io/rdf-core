// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset.hosting;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;

import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.hosting.DatasetCleanupOutcome;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetMaintenance;
import io.kogn.rdf.dataset.hosting.DatasetShutdownOutcome;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig.Persistence;
import lombok.extern.slf4j.Slf4j;

/**
 * RDF4J-backed {@link DatasetLifecycle}, and the {@link DatasetMaintenance} for the deletion leftover a failed
 * delete leaves behind in it.
 *
 * <p>Builds the backing store from a {@link DatasetStoreConfig}
 * ({@link MemoryStore} for {@code IN_MEMORY}, {@link NativeStore} for
 * {@code PERSISTENT}, default index spec {@code "spoc,posc,cosp"}) and never
 * exposes the RDF4J {@code Repository} — callers only ever see the neutral port
 * types via a leased {@link DatasetHandle}.</p>
 *
 * <p>For {@code IN_MEMORY} datasets a {@link MemoryStore} holds the only copy of
 * the data, so {@link #shutDown(DatasetId)} is destructive rather than a cheap,
 * resumable eviction: the store and its contents are gone, and the next
 * {@link #acquire(DatasetId)} builds a brand-new, empty one and re-runs the
 * on-create hook. See {@link DatasetLifecycle#shutDown(DatasetId)} for the full
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
 * per-key lock before the handle is handed out. {@link #shutDown(DatasetId)} and
 * {@link #delete(DatasetId)} run under the same per-key lock and inspect the
 * lease count, so a store can never be shut down or deleted while a handle is
 * open — this closes the time-of-check-to-time-of-use race that a bare
 * get-or-create + evict design suffers from.</p>
 *
 * <h2>A delete that fails</h2>
 *
 * <p>Removing a persistent dataset's storage is a directory walk, so it can fail
 * in the middle — a locked file, a permission problem — and leave a <em>deletion leftover</em>
 * behind. A deletion leftover must never be handed out: the directory is no longer empty, so the
 * store would be opened over whatever survived and the on-create hook would
 * <em>not</em> run, giving the caller a dataset that is neither the old one nor a
 * freshly seeded new one.</p>
 *
 * <p>{@link #delete(DatasetId)} therefore logs the failure at {@code ERROR} (in
 * addition to rethrowing it, which a caller may swallow) and marks the id as a deletion
 * leftover — every failed delete marks it this way, regardless of how
 * far the on-disk teardown got, even one that failed on its very first file. The
 * mark itself is a {@code .deleting} file written inside the dataset's own
 * directory before the teardown starts and removed by it on success, so it survives
 * a process restart and a second {@link DatasetLifecycleRdf4j} instance over the
 * same {@code storageRoot} — not just this instance. Writing that file is
 * best-effort: if it fails too (most likely for the same reason the deletion is
 * about to fail), an in-process set is the fallback, upholding the guarantee for
 * the rest of this process's life but not across a restart. The marker, the walk and the set
 * are the business of {@link DatasetStorage}.</p>
 *
 * <p>The next {@link #acquire(DatasetId)} first retries the cleanup: if it succeeds
 * the dataset is created afresh and seeded as usual, and if it fails again
 * {@code acquire} refuses the dataset with an {@link IllegalStateException} rather
 * than open the leftover. The same applies to the rollback of a failed creation.</p>
 *
 * <p>{@link #list()} leaves a marked id out for as long as the leftover is there,
 * even though its directory still exists: the listing reports what this
 * implementation can rule out, not a promise that {@link #acquire(DatasetId)}
 * would open everything it does not rule out — a marked id whose cleanup would in
 * fact now succeed is left out just the same.</p>
 *
 * <p>The marked ids are named instead by {@link #listDeletionLeftovers()}, and
 * {@link #clearDeletionLeftover(DatasetId)} retries the cleanup without creating
 * anything in its place — the way to clear it away when no caller
 * wants the dataset back. It refuses an id with a directory but no mark, which is an
 * intact dataset, and an id held open by this instance.</p>
 *
 * <h2>Path safety</h2>
 *
 * <p>The opaque {@link DatasetId} value is never used as a path. It is
 * Base64url-encoded into a single directory segment, so values such as
 * {@code "../etc"} cannot escape the storage root.</p>
 *
 * <p>{@link #list()} and {@link #listDeletionLeftovers()} read the ids back from the directory
 * names, and the storage root may hold directories this lifecycle did not create ({@code data},
 * {@code logs}). A directory name counts as ours only if it decodes as canonical Base64url, as
 * strictly valid UTF-8 (malformed input is rejected, not replaced), to a value {@link DatasetId}
 * accepts, and encodes back to exactly that name. This is a plausibility test, not proof of
 * origin: no marker file identifies a directory as a dataset, so a foreign directory whose name
 * happens to be a validly encoded id (such as {@code Zm9v}, the encoding of {@code foo}) is
 * listed as that id. Keep the {@code storageRoot} free of unrelated directories.</p>
 *
 * <p>Store creation runs under the per-key lock; for the expected workload
 * (few datasets, rare creation) holding the lock across store initialisation is
 * an acceptable trade for correctness.</p>
 */
@Slf4j
public final class DatasetLifecycleRdf4j implements DatasetLifecycle, DatasetMaintenance {

  /** Default RDF4J NativeStore triple-index specification. */
  public static final String DEFAULT_INDEX_SPEC = "spoc,posc,cosp";

  /** Message of the failure the {@code onCreate} hook's {@link GraphStore} throws once the hook returned. */
  private static final String HOOK_RETURNED = "the onCreate hook has returned; its GraphStore is no longer usable";

  private final DatasetStoreConfig config;
  private final DatasetStorage storage;
  private final String indexSpec;
  private final BiConsumer<DatasetId, GraphStore> onCreate;
  private final Consumer<Repository> repositoryShutdown;

  private final ConcurrentHashMap<DatasetId, ManagedDataset> datasets = new ConcurrentHashMap<>();

  /**
   * Held shared by an {@link #acquire(DatasetId)} while it may create a store, exclusively by
   * {@link #shutDownAll()}. A store still being created sits in {@code datasets.compute} where
   * iteration cannot see it; this lock is how {@code shutDownAll} waits for it to finish. The
   * {@code onCreate} hook runs under the shared side and is barred from calling back into the
   * lifecycle, so it can never wait on {@code shutDownAll}.
   */
  private final ReentrantReadWriteLock shutdownLock = new ReentrantReadWriteLock();

  /**
   * Set on the thread that is currently inside the {@code onCreate} hook, so a call back into this
   * lifecycle from that hook can be refused instead of re-entering {@code datasets.compute}.
   */
  private final ThreadLocal<Boolean> insideOnCreate = new ThreadLocal<>();

  /**
   * Creates a lifecycle.
   *
   * @param config backend-neutral store configuration; must not be {@code null}
   * @param storageRoot root directory under which persistent datasets live;
   *     required (non-{@code null}) for {@code PERSISTENT}, ignored for
   *     {@code IN_MEMORY}
   * @param indexSpec RDF4J NativeStore index specification; required for
   *     {@code PERSISTENT} (e.g. {@link #DEFAULT_INDEX_SPEC})
   * @param onCreate hook run when a dataset's store is first created, before its
   *     handle is handed out; receives the id and a {@link GraphStore} to seed
   *     through (never an RDF4J type), usable only while the hook runs: once it returns,
   *     every call on that {@code GraphStore} throws {@link IllegalStateException}, so it
   *     cannot be kept to write outside a lease. One-time per store, not per {@link DatasetId}:
   *     for {@code IN_MEMORY} a {@link #shutDown(DatasetId)} destroys the store, so the
   *     next {@link #acquire(DatasetId)} runs the hook again for the same id. It runs
   *     under the per-key map lock, so it must only seed its own
   *     {@code GraphStore} and must not call back into this
   *     lifecycle ({@code acquire}/{@code shutDown}/{@code delete}/{@code list}, nor the
   *     maintenance calls or {@code shutDownAll}). A call back on the hook's own thread is
   *     refused with an {@link IllegalStateException}, which then propagates as described below;
   *     a call from another thread that the hook starts and waits for is not detected and
   *     can deadlock. If it throws, creation is rolled back (store shut down,
   *     a newly created persistent store removed) and the exception propagates from
   *     {@code acquire}. May be {@code null}
   * @throws NullPointerException if {@code config} is {@code null}, or if
   *     {@code config.persistence()} is {@code PERSISTENT} and {@code storageRoot} or
   *     {@code indexSpec} is {@code null}
   */
  public DatasetLifecycleRdf4j(final DatasetStoreConfig config, final Path storageRoot, final String indexSpec,
      final BiConsumer<DatasetId, GraphStore> onCreate) {
    this(config, storageFor(config, storageRoot), indexSpec, onCreate, DatasetLifecycleRdf4j::shutDownIfInitialized);
  }

  /**
   * Creates a lifecycle over a given {@link DatasetStorage} and repository-shutdown step; the seam
   * through which a test replaces what a failing disk or a failing store teardown would do.
   *
   * @param config backend-neutral store configuration; must not be {@code null}
   * @param storage the on-disk side; required for {@code PERSISTENT}, ignored for {@code IN_MEMORY}
   * @param indexSpec RDF4J NativeStore index specification; required for {@code PERSISTENT}
   * @param onCreate the on-create hook; may be {@code null}
   * @param repositoryShutdown shuts a store down; must not be {@code null}
   */
  DatasetLifecycleRdf4j(final DatasetStoreConfig config, final DatasetStorage storage, final String indexSpec,
      final BiConsumer<DatasetId, GraphStore> onCreate, final Consumer<Repository> repositoryShutdown) {
    this.config = Objects.requireNonNull(config, "config");
    if (config.persistence() == Persistence.PERSISTENT) {
      this.storage = Objects.requireNonNull(storage, "storage is required for PERSISTENT");
      this.indexSpec = Objects.requireNonNull(indexSpec, "indexSpec is required for PERSISTENT");
    } else {
      this.storage = null;
      this.indexSpec = indexSpec;
    }
    this.onCreate = onCreate;
    this.repositoryShutdown = Objects.requireNonNull(repositoryShutdown, "repositoryShutdown");
  }

  private static DatasetStorage storageFor(final DatasetStoreConfig config, final Path storageRoot) {
    Objects.requireNonNull(config, "config");
    return config.persistence() == Persistence.PERSISTENT ? new DatasetStorage(storageRoot) : null;
  }

  /**
   * Convenience constructor using {@link #DEFAULT_INDEX_SPEC} and no on-create
   * hook.
   *
   * @param config backend-neutral store configuration
   * @param storageRoot root directory for persistent datasets
   * @throws NullPointerException if {@code config} is {@code null}, or if
   *     {@code config.persistence()} is {@code PERSISTENT} and {@code storageRoot} is {@code null}
   */
  public DatasetLifecycleRdf4j(final DatasetStoreConfig config, final Path storageRoot) {
    this(config, storageRoot, DEFAULT_INDEX_SPEC, null);
  }

  /**
   * {@inheritDoc}
   *
   * <p>If an earlier {@link #delete(DatasetId)} left a deletion leftover, this first
   * retries that cleanup and throws {@link IllegalStateException} if the leftover is still there
   * — see the class documentation.</p>
   *
   * @throws NullPointerException if {@code id} is {@code null}
   * @throws IllegalStateException if a failed delete left a deletion leftover that could not be cleaned up
   * @throws UncheckedIOException if the state of the dataset's storage cannot be determined
   *     (the storage is then left untouched)
   */
  @Override
  public DatasetHandle acquire(final DatasetId id) {
    Objects.requireNonNull(id, "id");
    requireNotInsideOnCreate("acquire");
    final ManagedDataset managed;
    shutdownLock.readLock().lock();
    try {
      managed = datasets.compute(id, (key, existing) -> {
        requireNoDeletionLeftover(key);
        final ManagedDataset md = existing != null ? existing : createAndSeed(key);
        md.leaseCount.incrementAndGet();
        return md;
      });
    } finally {
      shutdownLock.readLock().unlock();
    }
    return new LeasedDatasetHandle(managed);
  }

  @Override
  public DatasetShutdownOutcome shutDown(final DatasetId id) {
    Objects.requireNonNull(id, "id");
    requireNotInsideOnCreate("shutDown");
    final RuntimeException[] teardownFailure = new RuntimeException[1];
    final DatasetShutdownOutcome[] outcome = new DatasetShutdownOutcome[1];
    datasets.compute(id, (key, md) -> {
      if (md == null) {
        outcome[0] = DatasetShutdownOutcome.NOT_OPEN;
        return null;
      }
      if (md.leaseCount.get() > 0) {
        outcome[0] = DatasetShutdownOutcome.STILL_LEASED;
        return md; // in use — eviction is a no-op; policy retries later
      }
      try {
        repositoryShutdown.accept(md.repository);
        log.debug("Shut down dataset {}", key.value());
        outcome[0] = DatasetShutdownOutcome.SHUT_DOWN;
      } catch (final RuntimeException e) {
        // shutDown() is not exception-free; the store may now be half torn-down and unusable, so
        // the cache must not keep serving it to the next acquire() — drop the entry regardless and
        // let the real failure surface, instead of leaking a dead store under a live-looking key.
        teardownFailure[0] = e;
        // Unreachable today (teardownFailure wins below), but keeps the port's "never null"
        // promise true even if this path is ever reduced to a log statement.
        outcome[0] = DatasetShutdownOutcome.SHUT_DOWN;
      }
      return null;
    });
    if (teardownFailure[0] != null) {
      throw teardownFailure[0];
    }
    return outcome[0];
  }

  /**
   * {@inheritDoc}
   *
   * <p>A failure of the on-disk teardown is logged at {@code ERROR} as well as rethrown, and
   * marks the dataset as a deletion leftover so the next {@link #acquire(DatasetId)}
   * does not open it — see the class documentation.</p>
   *
   * <p>If removing the storage fails part-way, the failure is an {@link UncheckedIOException}
   * naming the path that could not be deleted; a failure of the store's own shutdown propagates
   * as RDF4J raised it. Either way the dataset is dropped from the cache.</p>
   *
   * @throws UncheckedIOException if removing the dataset's storage fails, leaving it half gone
   */
  @Override
  public void delete(final DatasetId id) {
    Objects.requireNonNull(id, "id");
    requireNotInsideOnCreate("delete");
    final RuntimeException[] teardownFailure = new RuntimeException[1];
    datasets.compute(id, (key, md) -> {
      if (md != null && md.leaseCount.get() > 0) {
        throw new IllegalStateException("cannot delete dataset '" + key.value() + "' with open leases");
      }
      try {
        if (md != null) {
          repositoryShutdown.accept(md.repository);
        }
        if (storage != null) {
          storage.deleteOrMarkDeletionLeftover(key);
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

  /**
   * {@inheritDoc}
   *
   * <p>The identifiers come from the in-memory cache plus every directory under the
   * {@code storageRoot} whose name this lifecycle itself would have produced for the id it
   * decodes to (canonical Base64url of strictly valid UTF-8 that {@link DatasetId} accepts) — a
   * foreign directory such as {@code data} or {@code logs} is skipped. Ids whose storage carries
   * the deletion-leftover mark are left out — see the class documentation for what that
   * does, and does not, promise about {@link #acquire(DatasetId)}.</p>
   *
   * @throws UncheckedIOException if the storage root cannot be listed, or if whether an
   *     id's storage carries the deletion-leftover mark cannot be determined
   */
  @Override
  public Set<DatasetId> list() {
    requireNotInsideOnCreate("list");
    final Set<DatasetId> result = new HashSet<>(datasets.keySet());
    if (storage != null) {
      result.addAll(storage.listDatasets());
    }
    result.removeIf(this::hasDeletionLeftover);
    return result;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The identifiers come from every directory under the {@code storageRoot} that carries the
   * {@value DatasetStorage#DELETION_MARKER_FILE_NAME} marker, plus the in-process fallback for a marker that could
   * not be written — see the class documentation. An {@code IN_MEMORY} lifecycle has no storage and
   * never reports any.</p>
   */
  @Override
  public Set<DatasetId> listDeletionLeftovers() {
    requireNotInsideOnCreate("listDeletionLeftovers");
    if (storage == null) {
      return Set.of();
    }
    return storage.listDeletionLeftovers();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Runs under the same per-key lock as {@link #acquire(DatasetId)} and {@link #delete(DatasetId)},
   * so it cannot race either for the same id. A failure of the cleanup is logged at {@code ERROR}
   * and rethrown, exactly as for a failed {@link #delete(DatasetId)}, and the mark stays.</p>
   *
   * @throws IllegalStateException if this instance holds the dataset open, or its directory exists
   *     without the deletion-leftover mark
   */
  @Override
  public DatasetCleanupOutcome clearDeletionLeftover(final DatasetId id) {
    Objects.requireNonNull(id, "id");
    requireNotInsideOnCreate("clearDeletionLeftover");
    final DatasetCleanupOutcome[] outcome = new DatasetCleanupOutcome[1];
    datasets.compute(id, (key, md) -> {
      if (md != null) {
        throw new IllegalStateException(
            "dataset '" + key.value() + "' is open; it is an intact dataset, which only delete() removes");
      }
      if (hasDeletionLeftover(key)) {
        storage.deleteOrMarkDeletionLeftover(key);
        log.info("Cleared the deletion leftover of dataset {}", key.value());
        outcome[0] = DatasetCleanupOutcome.CLEARED;
      } else if (storage != null && storage.hasDirectory(key)) {
        throw new IllegalStateException("dataset '" + key.value() + "' is an intact dataset, not a deletion"
            + " leftover; only delete() removes it");
      } else {
        outcome[0] = DatasetCleanupOutcome.NOTHING_TO_CLEAR;
      }
      return null;
    });
    return outcome[0];
  }

  /**
   * Shuts every open dataset down without deleting any storage. Intended for
   * orderly shutdown (e.g. {@code @PreDestroy} / test tear-down).
   *
   * <p><strong>Last resort — does not honour open leases.</strong> Unlike
   * {@link #shutDown(DatasetId)} and {@link #delete(DatasetId)}, this method does not
   * consult {@code leaseCount} or take the per-key lock: it tears every store down
   * unconditionally, including ones with an open {@link DatasetHandle}. Any handle
   * still open at that point keeps its {@code closed} flag {@code false} — its
   * accessors do not yet throw — but operating on the now-shut-down store fails
   * with the port's storage failure ({@code DatasetStorageException},
   * {@code SparqlEvaluationException} or {@code RdfExportException}); the store is not
   * started again, so an in-memory store does not come back empty and a persistent one
   * does not lock its directory again. Call this only when
   * the process is going down anyway (e.g. {@code @PreDestroy} or test tear-down),
   * never as a substitute for releasing leases in the normal course of business. If
   * any dataset still has an open lease, a warning is logged naming it before
   * teardown proceeds.</p>
   *
   * <p>A store that fails to shut down does not stop the others: every store is
   * attempted and the cache is cleared regardless, then the first failure is
   * rethrown with any further ones attached as suppressed exceptions.</p>
   *
   * <p>An {@link #acquire(DatasetId)} that is still creating its dataset's store is waited for
   * and its store shut down with the rest, so no store is left open. The lifecycle stays
   * usable: a later {@code acquire} of the same id opens (or, if persistent, reopens) the
   * dataset.</p>
   */
  @Override
  public void shutDownAll() {
    requireNotInsideOnCreate("shutDownAll");
    RuntimeException teardownFailure = null;
    shutdownLock.writeLock().lock();
    try {
      final Set<DatasetId> stillLeased = datasets.entrySet()
          .stream()
          .filter(entry -> entry.getValue().leaseCount.get() > 0)
          .map(Entry::getKey)
          .collect(Collectors.toSet());
      if (!stillLeased.isEmpty()) {
        log.warn("shutDownAll: tearing down {} dataset(s) with an open lease, ignoring in-flight protection: {}",
            stillLeased.size(), stillLeased);
      }
      for (final ManagedDataset md : datasets.values()) {
        try {
          repositoryShutdown.accept(md.repository);
        } catch (final RuntimeException e) {
          // the process is going down: one stuck store must not keep the rest open (a NativeStore
          // would keep its directory locked), so carry on and report every failure at the end.
          if (teardownFailure == null) {
            teardownFailure = e;
          } else {
            teardownFailure.addSuppressed(e);
          }
        }
      }
      datasets.clear();
    } finally {
      shutdownLock.writeLock().unlock();
    }
    if (teardownFailure != null) {
      throw teardownFailure;
    }
  }

  // ---------------------------------------------------------------------------

  private void requireNotInsideOnCreate(final String operation) {
    if (insideOnCreate.get() != null) {
      throw new IllegalStateException(operation + " called from within the onCreate hook: the hook runs under the "
          + "per-key map lock and must not call back into the lifecycle");
    }
  }

  private ManagedDataset createAndSeed(final DatasetId id) {
    final boolean isNew;
    final Repository repository;
    if (config.persistence() == Persistence.IN_MEMORY) {
      repository = new OneShotSailRepository(new MemoryStore());
      isNew = true;
    } else {
      isNew = storage.isNew(id);
      repository = new OneShotSailRepository(new NativeStore(storage.directoryOf(id), indexSpec));
    }
    try {
      repository.init();
      final ManagedDataset managed = new ManagedDataset(repository);
      if (isNew && onCreate != null) {
        insideOnCreate.set(Boolean.TRUE);
        final AtomicBoolean hookReturned = new AtomicBoolean(false);
        try {
          onCreate.accept(id,
              new LeasedDatasetHandle.HandleBoundGraphStore(managed.graphStore, hookReturned, HOOK_RETURNED));
        } finally {
          hookReturned.set(true);
          insideOnCreate.remove();
        }
      }
      return managed;
    } catch (final RuntimeException | Error e) {
      // init or the on-create seed failed (an Error from the hook included): don't leak the (possibly)
      // initialised store, and don't leave a half-created persistent store on disk — that would make
      // isNew false on the next acquire, so onCreate would never run again and the dataset would
      // stay unseeded. Restore the invariant: a dataset is created-and-seeded atomically, or not at all.
      try {
        repositoryShutdown.accept(repository);
      } catch (final RuntimeException teardownFailure) {
        // as below: report the failure that started this, and still try to remove the storage.
        e.addSuppressed(teardownFailure);
      }
      if (isNew && storage != null) {
        try {
          storage.deleteOrMarkDeletionLeftover(id);
        } catch (final RuntimeException rollbackFailure) {
          // the rollback itself failed, so the invariant cannot be restored here: the mark keeps
          // the leftover from being handed out. Report the failure that started this, not the one
          // from cleaning up after it.
          e.addSuppressed(rollbackFailure);
        }
      }
      throw e;
    }
  }

  private boolean hasDeletionLeftover(final DatasetId id) {
    return storage != null && storage.hasDeletionLeftover(id);
  }

  /**
   * Refuses to hand {@code id} out while a previous {@link #delete(DatasetId)} left it as a
   * deletion leftover — retrying the cleanup first, so that no caller is ever handed
   * the leftover.
   *
   * <p>Retrying the cleanup is the outcome to prefer: it leaves an empty directory, which
   * {@link DatasetStorage#isNew(DatasetId)} reads as "new dataset", so the store is created afresh and the
   * on-create hook runs. Cleaning up is not always available, though — the deletion may fail
   * again for the very reason it failed the first time — so refusing the dataset is the fallback
   * that always holds.</p>
   */
  private void requireNoDeletionLeftover(final DatasetId id) {
    if (!hasDeletionLeftover(id)) {
      return;
    }
    try {
      storage.deleteOrMarkDeletionLeftover(id);
    } catch (final RuntimeException e) {
      throw new IllegalStateException("dataset '" + id.value() + "' has a deletion leftover and it"
          + " could not be cleaned up; it cannot be acquired while it is there", e);
    }
  }

  /**
   * Shuts {@code repository} down if it is initialised. Any failure of the backend's
   * {@code shutDown()} propagates; each caller decides what that means for the cache.
   *
   * <p>The default of the repository-shutdown step that a package-private constructor lets a test
   * replace, to force a deterministic repository-teardown failure.</p>
   */
  static void shutDownIfInitialized(final Repository repository) {
    if (repository.isInitialized()) {
      repository.shutDown();
    }
  }
}
