// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.Triple;

/**
 * The preconditions of ni-rdf/1 §3, checked in the order the specification lists them, so that
 * the first failing one is the one reported. Every failure is an
 * {@link IllegalArgumentException} whose message starts with the failure code in brackets,
 * e.g. {@code [FOREIGN_SUBJECT]}.
 */
final class Preconditions {

  static final String INVALID_BASE = "INVALID_BASE";
  static final String RESERVED_IRI = "RESERVED_IRI";
  static final String EMPTY_GRAPH = "EMPTY_GRAPH";
  static final String UNSUPPORTED_TERM = "UNSUPPORTED_TERM";
  static final String FOREIGN_SUBJECT = "FOREIGN_SUBJECT";
  static final String NO_ROOT = "NO_ROOT";
  static final String UNREACHABLE = "UNREACHABLE";

  private static final char FRAGMENT_SEPARATOR = '#';

  private Preconditions() {
  }

  /**
   * Checks every precondition of ni-rdf/1 §3.
   *
   * @param base the base IRI {@code B}
   * @param triples the graph {@code G}
   * @param reserved the reserved IRI {@code R}
   * @return the IRI string of {@code base}
   * @throws IllegalArgumentException if {@code base} or {@code triples} is null, or for the
   *         first precondition that does not hold
   */
  static String check(IRI base, Collection<Triple> triples, String reserved) {
    if (base == null) {
      throw new IllegalArgumentException("Base IRI cannot be null");
    }
    if (triples == null) {
      throw new IllegalArgumentException("Graph cannot be null");
    }
    String baseIri = base.getIRIString();
    if (baseIri.indexOf(FRAGMENT_SEPARATOR) >= 0) {
      throw failure(INVALID_BASE,
          "A base IRI names the resource as a whole and carries no #fragment, but got <" + baseIri + ">");
    }
    if (baseIri.equals(reserved)) {
      throw failure(RESERVED_IRI, "The base IRI <" + baseIri + "> is the IRI ni-rdf/1 reserves for itself");
    }
    if (triples.isEmpty()) {
      throw failure(EMPTY_GRAPH, "The graph holds no triple");
    }
    rejectUnsupportedTerms(baseIri, triples);
    rejectReservedIris(triples, reserved);
    rejectForeignSubjects(triples, baseIri);
    rejectUnreachable(triples, baseIri);
    return baseIri;
  }

  private static IllegalArgumentException failure(String code, String message) {
    return new IllegalArgumentException("[" + code + "] " + message);
  }

  /**
   * Rejects a term that is no RDF 1.1 IRI, blank node or literal — an RDF 1.2 triple term, for
   * instance, or any other kind the term model may be extended with — and an IRI string that
   * starts with {@code _:}: no absolute IRI does, and the quad API would read it as a blank node.
   */
  private static void rejectUnsupportedTerms(String base, Collection<Triple> triples) {
    Stream.concat(Stream.of(base), triples.stream().flatMap(Preconditions::irisOf))
        .filter(iri -> iri.startsWith(Terms.BLANK_NODE_PREFIX))
        .findFirst()
        .ifPresent(iri -> {
          throw failure(UNSUPPORTED_TERM,
              "ni-rdf/1 admits only absolute IRIs, but <" + iri + "> reads as a blank node label");
        });
    triples.stream()
        .filter(t -> !isResource(t.getSubject()) || (!isResource(t.getObject()) && !(t.getObject() instanceof Literal)))
        .findFirst()
        .ifPresent(t -> {
          throw failure(UNSUPPORTED_TERM, "ni-rdf/1 admits only RDF 1.1 IRIs, blank nodes and literals, but got "
              + describe(t.getSubject()) + " / " + describe(t.getObject()));
        });
  }

  private static boolean isResource(RDFTerm term) {
    return term instanceof IRI || term instanceof BlankNode;
  }

  private static String describe(RDFTerm term) {
    return isResource(term) || term instanceof Literal ? term.ntriplesString() : term.getClass().getName();
  }

  /** Rejects an IRI in any position, datatype IRIs included, whose base is the reserved IRI. */
  private static void rejectReservedIris(Collection<Triple> triples, String reserved) {
    triples.stream()
        .flatMap(Preconditions::irisOf)
        .filter(iri -> Terms.baseOf(iri).equals(reserved))
        .findFirst()
        .ifPresent(iri -> {
          throw failure(RESERVED_IRI,
              "The graph uses <" + iri + ">, whose base is the IRI ni-rdf/1 reserves " + "for the resource itself");
        });
  }

  private static Stream<String> irisOf(Triple triple) {
    return Stream.of(triple.getSubject(), triple.getPredicate(), triple.getObject()).map(term -> switch (term) {
    case IRI iri -> iri.getIRIString();
    case Literal literal -> Terms.datatypeOf(literal);
    default -> null;
    }).filter(iri -> iri != null);
  }

  /**
   * Rejects IRI subjects that are neither the base nor a fragment IRI of it: their triples
   * describe another resource, and hashing them under this base would mix two resources into
   * one identifier.
   */
  private static void rejectForeignSubjects(Collection<Triple> triples, String base) {
    List<String> foreign = triples.stream()
        .map(Triple::getSubject)
        .filter(IRI.class::isInstance)
        .map(subject -> ((IRI) subject).getIRIString())
        .filter(subject -> !Terms.baseOf(subject).equals(base))
        .distinct()
        .sorted()
        .toList();
    if (!foreign.isEmpty()) {
      throw failure(FOREIGN_SUBJECT, "Content addressing describes exactly one resource, so every IRI subject "
          + "must be the base <" + base + "> or a fragment IRI of it, but " + foreign.size() + " are not: " + foreign);
    }
  }

  /**
   * Rejects a graph without IRI subject, and one holding triples no IRI subject reaches — a
   * free-standing blank node component, for instance. Such triples are legal RDF but would
   * contribute to no identifier, so two different graphs would silently share one.
   */
  private static void rejectUnreachable(Collection<Triple> triples, String base) {
    Map<BlankNodeOrIRI, List<Triple>> bySubject = triples.stream().collect(Collectors.groupingBy(Triple::getSubject));
    List<BlankNodeOrIRI> roots = bySubject.keySet().stream().filter(IRI.class::isInstance).toList();
    if (roots.isEmpty()) {
      throw failure(NO_ROOT, "Graph holds no subject that is the base <" + base + "> or a fragment IRI of it");
    }

    Set<BlankNodeOrIRI> visited = new HashSet<>();
    Queue<BlankNodeOrIRI> toVisit = new ArrayDeque<>(roots);
    while (!toVisit.isEmpty()) {
      BlankNodeOrIRI current = toVisit.poll();
      if (!visited.add(current)) {
        continue;
      }
      for (Triple triple : bySubject.getOrDefault(current, List.of())) {
        if (triple.getObject() instanceof BlankNode blankNode && !visited.contains(blankNode)) {
          toVisit.add(blankNode);
        }
      }
    }

    List<Triple> unreachable = triples.stream().filter(t -> !visited.contains(t.getSubject())).toList();
    if (!unreachable.isEmpty()) {
      throw failure(UNREACHABLE,
          "Graph holds " + unreachable.size() + " triple(s) not reachable from any IRI subject, which would "
              + "silently drop out of the content-addressed identifier; first one: " + unreachable.getFirst());
    }
  }
}
