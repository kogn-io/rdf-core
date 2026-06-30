// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;

import io.kogn.rdf.dataset.Dataset;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetStoreConfig;
import io.kogn.rdf.dataset.DatasetStoreConfig.Persistence;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import lombok.extern.slf4j.Slf4j;

/**
 * RDF4J-backed {@link DatasetLifecycle}.
 *
 * <p>Builds the backing store from a {@link DatasetStoreConfig}
 * ({@link MemoryStore} for {@code IN_MEMORY}, {@link NativeStore} for
 * {@code PERSISTENT}, default index spec {@code "spoc,posc,cosp"}) and never
 * exposes the RDF4J {@code Repository} — callers only ever see the neutral port
 * types via a leased {@link Dataset} handle.</p>
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
   *     {@code fullTextSearch == true} is rejected until FTS store assembly lands
   *     (changinggraph/rdf#67)
   * @param storageRoot root directory under which persistent datasets live;
   *     required (non-{@code null}) for {@code PERSISTENT}, ignored for
   *     {@code IN_MEMORY}
   * @param indexSpec RDF4J NativeStore index specification; required for
   *     {@code PERSISTENT} (e.g. {@link #DEFAULT_INDEX_SPEC})
   * @param onCreate one-time hook run when a dataset is first created, before its
   *     handle is handed out; receives the id and a {@link GraphStore} to seed
   *     through (never an RDF4J type). May be {@code null}
   * @throws UnsupportedOperationException if {@code config.fullTextSearch()} is
   *     {@code true}
   */
  public DatasetLifecycleRdf4j(final DatasetStoreConfig config, final Path storageRoot, final String indexSpec,
      final BiConsumer<DatasetId, GraphStore> onCreate) {
    this.config = Objects.requireNonNull(config, "config");
    if (config.fullTextSearch()) {
      throw new UnsupportedOperationException("FTS store assembly lands with #67");
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
  public Dataset acquire(final DatasetId id) {
    Objects.requireNonNull(id, "id");
    final ManagedDataset managed = datasets.compute(id, (key, existing) -> {
      final ManagedDataset md = existing != null ? existing : createAndSeed(key);
      md.leaseCount.incrementAndGet();
      return md;
    });
    return new LeasedDataset(managed);
  }

  @Override
  public void close(final DatasetId id) {
    Objects.requireNonNull(id, "id");
    datasets.compute(id, (key, md) -> {
      if (md == null) {
        return null;
      }
      if (md.leaseCount.get() > 0) {
        return md; // in use — eviction is a no-op; policy retries later
      }
      shutDownQuietly(md.repository);
      log.debug("Closed dataset {}", key.value());
      return null;
    });
  }

  @Override
  public void delete(final DatasetId id) {
    Objects.requireNonNull(id, "id");
    datasets.compute(id, (key, md) -> {
      if (md != null && md.leaseCount.get() > 0) {
        throw new IllegalStateException("cannot delete dataset '" + key.value() + "' with open leases");
      }
      if (md != null) {
        shutDownQuietly(md.repository);
      }
      if (config.persistence() != Persistence.IN_MEMORY) {
        deleteStorageOnDisk(key);
      }
      log.debug("Deleted dataset {}", key.value());
      return null;
    });
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
   */
  public void shutDownAll() {
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
    repository.init();
    final ManagedDataset managed = new ManagedDataset(repository);
    if (isNew && onCreate != null) {
      onCreate.accept(id, managed.graphStore);
    }
    return managed;
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
      return new DatasetId(new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8));
    } catch (final IllegalArgumentException e) {
      return null; // foreign directory not produced by this lifecycle — skip
    }
  }

  private void deleteStorageOnDisk(final DatasetId id) {
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

  private static void shutDownQuietly(final Repository repository) {
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
  private static final class LeasedDataset implements Dataset {

    private final ManagedDataset managed;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private LeasedDataset(final ManagedDataset managed) {
      this.managed = managed;
    }

    @Override
    public GraphStore graphStore() {
      return managed.graphStore;
    }

    @Override
    public SparqlQuery sparqlQuery() {
      return managed.sparqlQuery;
    }

    @Override
    public SparqlUpdate sparqlUpdate() {
      return managed.sparqlUpdate;
    }

    @Override
    public DatasetTransactor transactor() {
      return managed.transactor;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        managed.leaseCount.decrementAndGet();
      }
    }
  }
}
