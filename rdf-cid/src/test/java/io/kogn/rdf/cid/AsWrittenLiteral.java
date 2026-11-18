// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.cid;

import java.util.Optional;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.SimpleRdf;

/**
 * A language-tagged literal that reports its tag as written, the way a foreign {@link Literal} implementation that
 * does not normalize would. {@link SimpleRdf} lower-cases the tag on creation, so only such a term still reaches the
 * serializer with an upper-case tag, and only with it a test of ni-rdf/1 &sect;4.1 can fail.
 *
 * @param lexicalForm the lexical form
 * @param languageTag the language tag, kept in the case it is given in
 */
public record AsWrittenLiteral(String lexicalForm, String languageTag) implements Literal {

  private static final IRI LANG_STRING = new SimpleRdf().createLiteral("", "en").getDatatype();

  @Override
  public String getLexicalForm() {
    return lexicalForm;
  }

  @Override
  public IRI getDatatype() {
    return LANG_STRING;
  }

  @Override
  public Optional<String> getLanguageTag() {
    return Optional.of(languageTag);
  }

  @Override
  public String ntriplesString() {
    return "\"" + lexicalForm + "\"@" + languageTag;
  }
}
