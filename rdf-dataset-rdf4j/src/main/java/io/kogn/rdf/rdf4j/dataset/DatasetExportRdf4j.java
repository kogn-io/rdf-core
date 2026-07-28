// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset;

import java.io.OutputStream;
import java.util.Objects;

import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.Rio;

import io.kogn.rdf.dataset.DatasetExport;
import io.kogn.rdf.dataset.RdfExportException;
import io.kogn.rdf.dataset.RdfFormat;
import io.kogn.rdf.rdf4j.internal.RDF4JConverters;
import io.kogn.rdf.terms.IRI;

/**
 * RDF4J-based implementation of {@link DatasetExport}.
 *
 * <p>Each operation opens a dedicated {@link RepositoryConnection} from the underlying
 * {@link Repository} and closes it immediately after, following the same pattern as
 * {@link GraphStoreRdf4j} and {@link SparqlQueryRdf4j}.</p>
 *
 * <p><strong>Streamed, not materialised.</strong> The export runs through
 * {@link RepositoryConnection#export(org.eclipse.rdf4j.rio.RDFHandler, Resource...)} into a
 * {@link Rio}-created writer that writes straight to the caller's stream, so a statement is
 * serialized as it is read. No intermediate {@code Model} is built and no SPARQL is
 * evaluated — a dump therefore costs the store's own iteration, not a second copy of the
 * dataset on the heap, which is what makes exporting a dataset larger than memory possible
 * at all.</p>
 *
 * <p><strong>Asserted statements only.</strong> RDF4J's {@code export} reads with
 * {@code includeInferred=false}, so an export shows what was written, not what a
 * reasoning-capable {@link org.eclipse.rdf4j.sail.Sail Sail} would additionally derive. This
 * matches {@link GraphStoreRdf4j#export(IRI)} and diverges from the counts
 * {@code GraphStoreRdf4j} reports, which do fold inferred statements in; the
 * {@code MemoryStore}/{@code NativeStore} configurations this library ships never infer, so
 * the two views coincide today.</p>
 *
 * <p><strong>Namespaces travel along.</strong> {@code export} hands the writer every
 * namespace declaration the repository knows, so a Turtle or TriG dump carries {@code @prefix}
 * lines for them — including when the export itself contains no statement at all. "An empty
 * document" in the port's contract therefore means a document with no statements, not an
 * empty byte array.</p>
 *
 * <p><strong>Non-transactional.</strong> No transaction is opened: the export is a single
 * statement iteration over one connection, at whatever isolation the store applies to an
 * implicit read ({@code SNAPSHOT_READ} for the {@code MemoryStore}/{@code NativeStore}
 * configurations this library ships). Callers needing a dump that is atomic with other work
 * take a snapshot inside a transaction via {@link io.kogn.rdf.dataset.DatasetTx#export(IRI)}
 * and serialize that; see ADR-0013 for why export is not part of {@code DatasetTx}.</p>
 */
public class DatasetExportRdf4j implements DatasetExport {

  private final Repository repository;

  /**
   * Creates an export port backed by the given RDF4J repository.
   *
   * @param repository the repository to open connections against
   */
  public DatasetExportRdf4j(final Repository repository) {
    this.repository = repository;
  }

  @Override
  public void export(final OutputStream out, final RdfFormat format) {
    requireArguments(out, format);
    format.requireQuadCapable();
    writeTo(out, format);
  }

  @Override
  public void export(final OutputStream out, final RdfFormat format, final IRI namedGraph) {
    requireArguments(out, format);
    writeTo(out, format, RDF4JConverters.toRDF4JIRI(namedGraph));
  }

  private static void requireArguments(final OutputStream out, final RdfFormat format) {
    Objects.requireNonNull(out, "out must not be null");
    Objects.requireNonNull(format, "format must not be null");
  }

  /**
   * Streams the requested contexts into a writer for {@code format}.
   *
   * @param out the caller's stream; written to and flushed by the writer's {@code endRDF},
   *     never closed here — closing it would take that decision away from its owner
   * @param format the neutral format to serialize in
   * @param contexts the named graphs to export; empty means the entire repository, following
   *     RDF4J's own vararg-context convention
   * @throws RdfExportException if the writer cannot write to {@code out}
   */
  private void writeTo(final OutputStream out, final RdfFormat format, final Resource... contexts) {
    try (RepositoryConnection conn = repository.getConnection()) {
      conn.export(Rio.createWriter(toRDF4JFormat(format), out), contexts);
    } catch (final RDFHandlerException e) {
      // Rio wraps the sink's IOException in an RDFHandlerException; keep it as cause so the
      // caller can see what the stream did, without an RDF4J type on the port.
      throw new RdfExportException("failed to write the " + format + " export to the output stream", e);
    }
  }

  /**
   * Maps the port's format to RDF4J's. Exhaustive over {@link RdfFormat} on purpose: adding a
   * constant there must fail this switch to compile rather than fall through to a default.
   *
   * @param format the neutral format
   * @return the equivalent RDF4J format
   */
  private static RDFFormat toRDF4JFormat(final RdfFormat format) {
    return switch (format) {
    case TURTLE -> RDFFormat.TURTLE;
    case NTRIPLES -> RDFFormat.NTRIPLES;
    case TRIG -> RDFFormat.TRIG;
    case NQUADS -> RDFFormat.NQUADS;
    };
  }
}
