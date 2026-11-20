// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.util.Objects;

import io.kogn.rdf.cid.ContentAddressedIRIGenerator;
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
 * ({@code <new#f>}), in every position: subject, predicate, object and the datatype of a literal without a language
 * tag (the datatype {@code rdf:langString} of a tagged literal is not renamed).
 * The operation works on terms, never on text: an IRI that merely begins with the old base
 * ({@code <old-other>}) and a literal whose lexical form spells the old base are content and
 * stay as they are. A literal whose datatype is not given reads as {@code xsd:string}, as in
 * ni-rdf/1 §4.1, and is renamed as such. Blank nodes, lexical forms and language tags are
 * carried over unchanged.</p>
 *
 * <p>This is the step between {@link ContentAddressedIRIGenerator#generateIRI} and a
 * resource that carries its own identifier: describe it under a provisional base, generate the
 * identifier, rebase the graph onto the identifier. Generating again over the result, with the
 * identifier as base, returns the same identifier, so a reader holding the description and
 * the identifier can verify that one belongs to the other. The operation does not depend on
 * the digest procedure and needs no generator.</p>
 */
public final class BaseRebaser {

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
   * {@link ContentAddressedIRIGenerator#generateIRI}: a base carries no {@code #}, is not
   * the IRI ni-rdf/1 reserves for its placeholder, holds no unpaired surrogate and does not
   * read as a blank node label ({@code _:}). The graph is not required to describe
   * {@code from} alone; IRIs of other resources are carried over unchanged. It must not
   * already use the new base, though: its IRIs would merge with the renamed ones, and the
   * rename could no longer be undone.</p>
   *
   * @param graph the graph to rename
   * @param from the base IRI to replace; carries no {@code #}
   * @param to the base IRI that takes its place; carries no {@code #}
   * @return a new graph holding the renamed triples, as many as {@code graph} holds
   * @throws NullPointerException if an argument is null
   * @throws IllegalArgumentException if {@code from} or {@code to} carries a {@code #} ({@code [INVALID_BASE]}), is the reserved IRI
   *         ({@code [RESERVED_IRI]}), holds an unpaired surrogate or begins with {@code _:}
   *         ({@code [UNSUPPORTED_TERM]}); if {@code to} differs from {@code from} and the graph
   *         already uses an IRI whose base is {@code to}; if a term is none of an RDF 1.1 IRI,
   *         blank node or literal ({@code [UNSUPPORTED_TERM]}, also a null triple or term); or if the term factory rejects a renamed IRI
   */
  public Graph rebase(ReadableGraph graph, IRI from, IRI to) {
    Objects.requireNonNull(graph, "graph must not be null");
    final String fromIRI = checkedBase(from, "old");
    final String toIRI = checkedBase(to, "new");
    final Graph result = rdf.createGraph();
    graph.stream().forEach(triple -> {
      if (triple == null) {
        throw Preconditions.failure(Preconditions.UNSUPPORTED_TERM, "The graph holds a null triple");
      }
      result.add(renamed(triple, fromIRI, toIRI));
    });
    return result;
  }

  private static String checkedBase(IRI base, String which) {
    Objects.requireNonNull(base, () -> "the " + which + " base IRI must not be null");
    return Preconditions.checkBase(base, which + " base IRI");
  }

  private Triple renamed(Triple triple, String from, String to) {
    return rdf.createTriple((BlankNodeOrIRI) renamed(triple.getSubject(), from, to),
        (IRI) renamed(triple.getPredicate(), from, to), renamed(triple.getObject(), from, to));
  }

  private RDFTerm renamed(RDFTerm term, String from, String to) {
    if (term == null) {
      throw Preconditions.failure(Preconditions.UNSUPPORTED_TERM, "The graph holds a null term");
    }
    return switch (term) {
    case IRI iri -> renamed(iri, from, to);
    case BlankNode blankNode -> rdf.createBlankNode(blankNode.uniqueReference());
    case Literal literal -> renamed(literal, from, to);
    default -> throw new IllegalArgumentException("[" + Preconditions.UNSUPPORTED_TERM
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
    return rdf.createLiteral(literal.getLexicalForm(), rdf.createIRI(renamed(Terms.datatypeOf(literal), from, to)));
  }

  private static String renamed(String iri, String from, String to) {
    final String base = Terms.baseOf(iri);
    final int separator = base.length() < iri.length() ? base.length() : -1;
    if (base.equals(from)) {
      return to + (separator < 0 ? "" : iri.substring(separator));
    }
    if (base.equals(to)) {
      throw new IllegalArgumentException("The graph already uses <" + iri + ">, whose base is the new base <" + to
          + ">; renaming <" + from + "> to it would merge distinct IRIs");
    }
    return iri;
  }
}
