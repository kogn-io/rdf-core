// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import java.util.Objects;

import io.kogn.rdf.cid.sexpr.ContentAddressableRdfSerializer;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.Triple;

/**
 * Renames the base IRI of a graph, as ni-rdf/1 §6 ("Renaming") describes it.
 *
 * <p>The old base IRI becomes the new one and every fragment IRI of the old base
 * ({@code <old#f>}) becomes the new base followed by {@code #} and the same fragment
 * ({@code <new#f>}), in every position: subject, predicate, object and a literal's datatype.
 * The operation works on terms, never on text: an IRI that merely begins with the old base
 * ({@code <old-other>}) and a literal whose lexical form spells the old base are content and
 * stay as they are. Blank nodes, lexical forms and language tags are carried over unchanged.</p>
 *
 * <p>This is the step between {@link ContentAddressedIriGenerator#generateIri} and a
 * resource that carries its own identifier: describe it under a provisional base, generate the
 * identifier, rebase the graph onto the identifier. Generating again over the result, with the
 * identifier as base, returns the same identifier, so a reader holding the description and
 * the identifier can verify that one belongs to the other. The operation does not depend on
 * the digest procedure and needs no generator.</p>
 */
public class BaseRebaser {

  private static final char FRAGMENT_SEPARATOR = '#';
  private static final String INVALID_BASE = "INVALID_BASE";
  private static final String RESERVED_IRI = "RESERVED_IRI";
  private static final String UNSUPPORTED_TERM = "UNSUPPORTED_TERM";
  private static final String BLANK_NODE_PREFIX = "_:";

  private final RDF rdf;

  /**
   * Creates a rebaser.
   *
   * @param rdf the term factory used to build the renamed terms and the result graph
   */
  public BaseRebaser(RDF rdf) {
    this.rdf = Objects.requireNonNull(rdf, "rdf must not be null");
  }

  /**
   * Returns a copy of {@code graph} in which {@code from} and its fragment IRIs are replaced by
   * {@code to} and its fragment IRIs.
   *
   * <p>The graph itself is not changed. The bases are checked like the base of
   * {@link ContentAddressedIriGenerator#generateIri}: a base carries no {@code #}, is not
   * the IRI ni-rdf/1 reserves for its placeholder and does not read as a blank node label
   * ({@code _:}). The graph is not required to describe
   * {@code from} alone; IRIs of other resources are carried over unchanged. It must not
   * already use the new base, though: its IRIs would merge with the renamed ones, and the
   * rename could no longer be undone.</p>
   *
   * @param graph the graph to rename
   * @param from the base IRI to replace; carries no {@code #}
   * @param to the base IRI that takes its place; carries no {@code #}
   * @return a new graph holding the renamed triples, as many as {@code graph} holds
   * @throws IllegalArgumentException if an argument is null; if {@code from} or {@code to}
   *         carries a {@code #} ({@code [INVALID_BASE]}), is the reserved IRI
   *         ({@code [RESERVED_IRI]}) or begins with {@code _:} ({@code [UNSUPPORTED_TERM]}); if {@code to} differs from {@code from} and the graph
   *         already uses an IRI whose base is {@code to}; if a term is none of an RDF 1.1 IRI,
   *         blank node or literal ({@code [UNSUPPORTED_TERM]}, also a null triple or term); or if the term factory rejects a renamed IRI
   */
  public Graph rebase(ReadableGraph graph, IRI from, IRI to) {
    if (graph == null) {
      throw new IllegalArgumentException("Graph cannot be null");
    }
    final String fromIri = checkedBase(from, "old");
    final String toIri = checkedBase(to, "new");
    final Graph result = rdf.createGraph();
    graph.stream().forEach(triple -> {
      if (triple == null) {
        throw failure(UNSUPPORTED_TERM, "The graph holds a null triple");
      }
      result.add(renamed(triple, fromIri, toIri));
    });
    return result;
  }

  private static String checkedBase(IRI base, String which) {
    if (base == null) {
      throw new IllegalArgumentException("The " + which + " base IRI cannot be null");
    }
    final String iri = base.getIRIString();
    if (iri == null) {
      throw failure(UNSUPPORTED_TERM, "The " + which + " base IRI has no IRI string");
    }
    if (iri.indexOf(FRAGMENT_SEPARATOR) >= 0) {
      throw failure(INVALID_BASE, "The " + which + " base IRI carries no #fragment, but got <" + iri + ">");
    }
    if (iri.equals(ContentAddressableRdfSerializer.RESERVED_IRI)) {
      throw failure(RESERVED_IRI, "The " + which + " base IRI <" + iri + "> is the IRI ni-rdf/1 reserves for itself");
    }
    if (iri.startsWith(BLANK_NODE_PREFIX)) {
      throw failure(UNSUPPORTED_TERM,
          "ni-rdf/1 admits only absolute IRIs, but <" + iri + "> reads as a blank node label");
    }
    return iri;
  }

  private Triple renamed(Triple triple, String from, String to) {
    return rdf.createTriple((BlankNodeOrIRI) renamed(triple.getSubject(), from, to),
        (IRI) renamed(triple.getPredicate(), from, to), renamed(triple.getObject(), from, to));
  }

  private RDFTerm renamed(RDFTerm term, String from, String to) {
    if (term == null) {
      throw failure(UNSUPPORTED_TERM, "The graph holds a null term");
    }
    return switch (term) {
    case IRI iri -> renamed(iri, from, to);
    case BlankNode blankNode -> rdf.createBlankNode(blankNode.uniqueReference());
    case Literal literal -> renamed(literal, from, to);
    default -> throw new IllegalArgumentException("[" + UNSUPPORTED_TERM
        + "] Only RDF 1.1 IRIs, blank nodes and literals can be renamed, but got " + term.getClass().getName());
    };
  }

  private IRI renamed(IRI iri, String from, String to) {
    return rdf.createIRI(renamed(iri.getIRIString(), from, to));
  }

  private Literal renamed(Literal literal, String from, String to) {
    if (literal.getLanguageTag().isPresent()) {
      return rdf.createLiteral(literal.getLexicalForm(), literal.getLanguageTag().get());
    }
    if (literal.getDatatype() == null) {
      return rdf.createLiteral(literal.getLexicalForm());
    }
    return rdf.createLiteral(literal.getLexicalForm(), renamed(literal.getDatatype(), from, to));
  }

  private static String renamed(String iri, String from, String to) {
    final int separator = iri.indexOf(FRAGMENT_SEPARATOR);
    final String base = separator < 0 ? iri : iri.substring(0, separator);
    if (base.equals(from)) {
      return to + (separator < 0 ? "" : iri.substring(separator));
    }
    if (base.equals(to)) {
      throw new IllegalArgumentException("The graph already uses <" + iri + ">, whose base is the new base <" + to
          + ">; renaming <" + from + "> to it would merge distinct IRIs");
    }
    return iri;
  }

  private static IllegalArgumentException failure(String code, String message) {
    return new IllegalArgumentException("[" + code + "] " + message);
  }
}
