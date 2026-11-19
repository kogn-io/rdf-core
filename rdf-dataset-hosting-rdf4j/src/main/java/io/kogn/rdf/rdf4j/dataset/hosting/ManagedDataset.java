// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset.hosting;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.rdf4j.repository.Repository;

import io.kogn.rdf.dataset.DatasetExport;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.rdf4j.dataset.DatasetExportRdf4j;
import io.kogn.rdf.rdf4j.dataset.DatasetTransactorRdf4j;
import io.kogn.rdf.rdf4j.dataset.GraphStoreRdf4j;
import io.kogn.rdf.rdf4j.dataset.SparqlQueryRdf4j;
import io.kogn.rdf.rdf4j.dataset.SparqlUpdateRdf4j;

/** A cached, leasable dataset: its store and the five port wrappers over it. */
final class ManagedDataset {

  final Repository repository;
  final GraphStore graphStore;
  final SparqlQuery sparqlQuery;
  final SparqlUpdate sparqlUpdate;
  final DatasetExport datasetExport;
  final DatasetTransactor transactor;
  final AtomicInteger leaseCount = new AtomicInteger();

  ManagedDataset(final Repository repository) {
    this.repository = repository;
    this.graphStore = new GraphStoreRdf4j(repository);
    this.sparqlQuery = new SparqlQueryRdf4j(repository);
    this.sparqlUpdate = new SparqlUpdateRdf4j(repository);
    this.datasetExport = new DatasetExportRdf4j(repository);
    this.transactor = new DatasetTransactorRdf4j(repository);
  }
}
