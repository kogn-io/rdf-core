// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset;

/**
 * RDF serialization formats a {@link DatasetExport} can write.
 *
 * <p>Deliberately a small, closed set of W3C text formats rather than a mirror of whatever
 * a backend happens to register: the port promises these four, every implementation has to
 * support exactly these four, and a consumer picking one of them never depends on a
 * backend's format registry. It also keeps the port free of a backend's own format type
 * (RDF4J's {@code org.eclipse.rdf4j.rio.RDFFormat}), which is the whole point of this
 * module.</p>
 *
 * <p>The distinction that matters here is whether a format can carry the <em>graph name</em>
 * of a statement: {@link #TRIG} and {@link #NQUADS} can (they serialize quads),
 * {@link #TURTLE} and {@link #NTRIPLES} cannot (they serialize triples of a single graph).
 * A whole-dataset export therefore needs a quad-capable format — see
 * {@link #isQuadCapable()} and {@link #requireQuadCapable()}, which is the shared
 * enforcement point implementations of {@link DatasetExport} call so the rejection message
 * is the same across backends.</p>
 */
public enum RdfFormat {

  /**
   * <a href="https://www.w3.org/TR/turtle/">Turtle</a> — triples of one graph, no graph
   * names.
   */
  TURTLE,

  /**
   * <a href="https://www.w3.org/TR/n-triples/">N-Triples</a> — one triple per line, no
   * graph names.
   */
  NTRIPLES,

  /**
   * <a href="https://www.w3.org/TR/trig/">TriG</a> — Turtle extended with graph blocks;
   * carries graph names.
   */
  TRIG,

  /**
   * <a href="https://www.w3.org/TR/n-quads/">N-Quads</a> — one quad per line; carries graph
   * names.
   */
  NQUADS;

  /**
   * Reports whether this format can carry the graph name of a statement.
   *
   * <p>Only a quad-capable format can represent more than one named graph in a single
   * document; a triple-only format would flatten every named graph into one, silently
   * losing the graph each statement belonged to.</p>
   *
   * @return {@code true} for {@link #TRIG} and {@link #NQUADS}, {@code false} for
   *     {@link #TURTLE} and {@link #NTRIPLES}
   */
  public boolean isQuadCapable() {
    return this == TRIG || this == NQUADS;
  }

  /**
   * Fails unless this format {@linkplain #isQuadCapable() can carry graph names}.
   *
   * <p>The guard behind {@link DatasetExport#export(java.io.OutputStream, RdfFormat)}: it
   * lives here, on the value being validated, rather than in each backend, so every
   * implementation rejects the same inputs with the same message instead of drifting
   * apart.</p>
   *
   * @throws IllegalArgumentException if this format is triple-only
   */
  public void requireQuadCapable() {
    if (!isQuadCapable()) {
      throw new IllegalArgumentException(
          name() + " is a triple-only format and cannot represent named graphs; use " + TRIG + " or " + NQUADS);
    }
  }
}
