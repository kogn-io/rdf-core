// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;

/**
 * Term-level rules of ni-rdf/1 shared by the preconditions, the resource limit, the
 * canonicalization and the serialization, so that all four read a term the same way.
 */
final class Terms {

  /** Datatype IRI of a literal without language tag and without a given datatype (RDF 1.1). */
  static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

  /** Datatype IRI of every language-tagged literal (RDF 1.1). */
  static final String RDF_LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString";

  /** Prefix that marks a blank node identifier in the titanium quad API and in N-Quads. */
  static final String BLANK_NODE_PREFIX = "_:";

  private static final char FRAGMENT_SEPARATOR = '#';

  private Terms() {
  }

  /**
   * The datatype IRI of a literal as ni-rdf/1 §4.1 fixes it: {@code rdf:langString} for a
   * language-tagged literal, {@code xsd:string} for a literal without datatype, the given
   * datatype otherwise.
   */
  static String datatypeOf(Literal literal) {
    if (literal.getLanguageTag().isPresent()) {
      return RDF_LANG_STRING;
    }
    IRI datatype = literal.getDatatype();
    return datatype == null ? XSD_STRING : datatype.getIRIString();
  }

  /** The base of an IRI string: the part before the first {@code #}, or the whole string. */
  static String baseOf(String iri) {
    int separator = iri.indexOf(FRAGMENT_SEPARATOR);
    return separator < 0 ? iri : iri.substring(0, separator);
  }

  /**
   * Lower-cases a language tag the way ni-rdf/1 §4.1 prescribes: {@code A}–{@code Z} become
   * {@code a}–{@code z}, every other character stays as it is. Deliberately not
   * {@link String#toLowerCase}, which also maps characters outside that range.
   */
  static String lowerCaseLanguageTag(String tag) {
    StringBuilder lower = new StringBuilder(tag.length());
    for (int i = 0; i < tag.length(); i++) {
      char c = tag.charAt(i);
      lower.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
    }
    return lower.toString();
  }

  /** The identifier of a blank node in the titanium quad API: {@code _:} and its reference. */
  static String blankNodeId(BlankNode blankNode) {
    return BLANK_NODE_PREFIX + blankNode.uniqueReference();
  }

  /**
   * The resource string of an IRI or blank node in the titanium quad API: an IRI by its IRI
   * string, a blank node by {@link #blankNodeId}.
   */
  static String resource(RDFTerm term) {
    return switch (term) {
    case IRI iri -> iri.getIRIString();
    case BlankNode blankNode -> blankNodeId(blankNode);
    default -> throw new IllegalStateException("Not an IRI or blank node: " + term.getClass());
    };
  }
}
