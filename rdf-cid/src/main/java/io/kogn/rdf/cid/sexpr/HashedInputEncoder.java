// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid.sexpr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.Triple;

/**
 * Encodes the hashed input of ni-rdf/1 §4.5: a sorted S-expression of length-prefixed strings,
 * opened by the procedure tag. The reserved IRI is written as {@code S}, a fragment IRI of it as
 * {@code F} and the fragment, any other IRI as {@code I} and its string, a literal as {@code L},
 * lexical form, datatype IRI and language tag, a blank node as {@code B} and its canonical
 * issued identifier.
 */
final class HashedInputEncoder {

  /** The procedure tag that opens the hashed input (ni-rdf/1 §4.5). */
  static final String PROCEDURE_TAG = "ni-rdf/1";

  /** Term kind tags written into the hashed input so terms of different kinds cannot collide. */
  private static final String KIND_IRI = "I";
  private static final String KIND_LITERAL = "L";
  private static final String KIND_BLANK = "B";
  private static final String KIND_SELF = "S";
  private static final String KIND_FRAGMENT = "F";

  private static final byte OPEN = '(';
  private static final byte CLOSE = ')';
  private static final byte LENGTH_SEPARATOR = ':';

  private HashedInputEncoder() {
  }

  /**
   * Encodes the hashed input: the procedure tag and the sorted triple encodings.
   *
   * @param mappedGraph the mapped graph (§1.2)
   * @param identifiers the canonical issued identifier of every blank node of {@code mappedGraph}
   * @return the bytes of §4.5
   */
  static byte[] encode(List<Triple> mappedGraph, Map<String, String> identifiers) {
    List<byte[]> encodings = mappedGraph.stream()
        .map(triple -> encoded(triple, identifiers))
        .sorted(Arrays::compareUnsigned)
        .toList();

    ByteArrayOutputStream hashedInput = new ByteArrayOutputStream();
    hashedInput.write(OPEN);
    writeString(hashedInput, PROCEDURE_TAG);
    encodings.forEach(hashedInput::writeBytes);
    hashedInput.write(CLOSE);
    return hashedInput.toByteArray();
  }

  /** One triple as {@code (}, subject, predicate, object, {@code )}. */
  private static byte[] encoded(Triple triple, Map<String, String> identifiers) {
    ByteArrayOutputStream encoding = new ByteArrayOutputStream();
    encoding.write(OPEN);
    writeTerm(encoding, triple.getSubject(), identifiers);
    writeTerm(encoding, triple.getPredicate(), identifiers);
    writeTerm(encoding, triple.getObject(), identifiers);
    encoding.write(CLOSE);
    return encoding.toByteArray();
  }

  /**
   * Writes a term as its kind tag followed by its components, every one a length-prefixed
   * string, so the concatenation is unambiguous and no value can spell a placeholder.
   */
  private static void writeTerm(ByteArrayOutputStream out, RDFTerm term, Map<String, String> identifiers) {
    switch (term) {
    case IRI iri -> writeIRI(out, iri.getIRIString());
    case Literal literal -> {
      writeString(out, KIND_LITERAL);
      writeString(out, literal.getLexicalForm());
      writeIRI(out, Terms.datatypeOf(literal));
      writeString(out, literal.getLanguageTag().orElse(""));
    }
    case BlankNode blankNode -> {
      String identifier = identifiers.get(blankNode.uniqueReference());
      if (identifier == null) {
        throw new IllegalStateException("RDFC-1.0 issued no identifier for blank node " + blankNode.uniqueReference());
      }
      writeString(out, KIND_BLANK);
      writeString(out, identifier);
    }
    default -> throw Preconditions.unsupportedKind(term);
    }
  }

  /** The reserved IRI as {@code S}, {@code reserved#f} as {@code F} and {@code f}, any other IRI as {@code I}. */
  private static void writeIRI(ByteArrayOutputStream out, String iri) {
    if (iri.equals(Terms.RESERVED_IRI)) {
      writeString(out, KIND_SELF);
      return;
    }
    if (iri.startsWith(Terms.RESERVED_FRAGMENT_PREFIX)) {
      writeString(out, KIND_FRAGMENT);
      writeString(out, iri.substring(Terms.RESERVED_FRAGMENT_PREFIX.length()));
      return;
    }
    writeString(out, KIND_IRI);
    writeString(out, iri);
  }

  /** {@code LS(x)}: the UTF-8 byte length in ASCII decimal, {@code :}, the UTF-8 bytes. */
  private static void writeString(ByteArrayOutputStream out, String value) {
    byte[] data = value.getBytes(StandardCharsets.UTF_8);
    out.writeBytes(Integer.toString(data.length).getBytes(StandardCharsets.US_ASCII));
    out.write(LENGTH_SEPARATOR);
    out.writeBytes(data);
  }
}
