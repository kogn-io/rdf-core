package io.kogn.rdf.terms;

import java.util.Optional;

/**
 * Represents a Literal value in RDF.
 *
 * <p>Literals contain textual or numeric values and may have an associated language tag
 * or datatype IRI.</p>
 */
public interface Literal extends RDFTerm {

  /**
   * Returns the lexical form of the literal (the actual string value).
   *
   * @return the lexical form
   */
  String getLexicalForm();

  /**
   * Returns the datatype IRI of this literal.
   *
   * @return the datatype IRI (e.g., xsd:string, xsd:integer)
   */
  IRI getDatatype();

  /**
   * Returns the language tag if present.
   *
   * @return Optional containing the language tag (e.g., "en", "de"), or empty if not present
   */
  Optional<String> getLanguageTag();
}
