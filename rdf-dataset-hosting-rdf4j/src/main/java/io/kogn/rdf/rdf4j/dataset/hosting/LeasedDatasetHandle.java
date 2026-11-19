// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset.hosting;

import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetExport;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.RdfFormat;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;

/** Lease handle: releases its lease exactly once on {@link #close()}. */
final class LeasedDatasetHandle implements DatasetHandle {

  /** Message of the failure a port of a closed {@link DatasetHandle} throws. */
  private static final String HANDLE_CLOSED = "handle is closed";

  private final ManagedDataset managed;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final GraphStore graphStore;
  private final SparqlQuery sparqlQuery;
  private final SparqlUpdate sparqlUpdate;
  private final DatasetExport datasetExport;
  private final DatasetTransactor transactor;

  LeasedDatasetHandle(final ManagedDataset managed) {
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

  /**
   * Throws {@link IllegalStateException} if {@code closed} has already been set,
   * enforcing the "do not retain beyond the handle" rule stated on
   * {@link DatasetHandle}.
   */
  private static void ensureOpen(final AtomicBoolean closed) {
    ensureOpen(closed, HANDLE_CLOSED);
  }

  private static void ensureOpen(final AtomicBoolean closed, final String message) {
    if (closed.get()) {
      throw new IllegalStateException(message);
    }
  }

  /**
   * Thin, per-handle {@link GraphStore} delegate that checks the owning handle's
   * {@code closed} flag before every call. Also hands the {@code onCreate} hook its
   * {@code GraphStore}, with a flag set once the hook returns: kept beyond the hook, that store
   * would write outside every lease — after a {@code delete}, a write would bring the deleted
   * directory back.
   */
  static final class HandleBoundGraphStore implements GraphStore {

    private final GraphStore delegate;
    private final AtomicBoolean closed;
    private final String closedMessage;

    private HandleBoundGraphStore(final GraphStore delegate, final AtomicBoolean closed) {
      this(delegate, closed, HANDLE_CLOSED);
    }

    HandleBoundGraphStore(final GraphStore delegate, final AtomicBoolean closed, final String closedMessage) {
      this.delegate = delegate;
      this.closed = closed;
      this.closedMessage = closedMessage;
    }

    @Override
    public long add(final IRI namedGraph, final ReadableGraph triples) {
      ensureOpen(closed, closedMessage);
      return delegate.add(namedGraph, triples);
    }

    @Override
    public long remove(final IRI namedGraph, final ReadableGraph triples) {
      ensureOpen(closed, closedMessage);
      return delegate.remove(namedGraph, triples);
    }

    @Override
    public void clear(final IRI namedGraph) {
      ensureOpen(closed, closedMessage);
      delegate.clear(namedGraph);
    }

    @Override
    public ReadableGraph snapshot(final IRI namedGraph) {
      ensureOpen(closed, closedMessage);
      return delegate.snapshot(namedGraph);
    }

    @Override
    public long count(final IRI namedGraph) {
      ensureOpen(closed, closedMessage);
      return delegate.count(namedGraph);
    }

    @Override
    public long count() {
      ensureOpen(closed, closedMessage);
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
