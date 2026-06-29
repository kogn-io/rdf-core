package io.kogn.rdf.terms.vocab;

import io.kogn.rdf.terms.IRI;

/**
 * XML Schema Datatypes (XSD) vocabulary terms.
 *
 * <p>Provides constants for common XSD datatypes used in RDF literals.
 * This vocabulary is defined at <a href="http://www.w3.org/2001/XMLSchema#">
 * http://www.w3.org/2001/XMLSchema#</a></p>
 *
 * @see <a href="https://www.w3.org/TR/xmlschema11-2/">W3C XML Schema Part 2: Datatypes</a>
 */
public interface VocabXsd {
  /** The XSD namespace: {@value} */
  String NAMESPACE = "http://www.w3.org/2001/XMLSchema#";

  /** The {@code xsd:float} datatype for single-precision floating point numbers */
  IRI FLOAT = VocabIriFactory.createIRI(NAMESPACE, "float");

  /** The {@code xsd:string} datatype for character strings */
  IRI STRING = VocabIriFactory.createIRI(NAMESPACE, "string");

  /** The {@code xsd:integer} datatype for arbitrary-size integer numbers */
  IRI INTEGER = VocabIriFactory.createIRI(NAMESPACE, "integer");

  /** The {@code xsd:nonNegativeInteger} datatype for non-negative integer numbers (0 or positive) */
  IRI NON_NEGATIVE_INTEGER = VocabIriFactory.createIRI(NAMESPACE, "nonNegativeInteger");

  /** The {@code xsd:decimal} datatype for arbitrary-precision decimal numbers */
  IRI DECIMAL = VocabIriFactory.createIRI(NAMESPACE, "decimal");

  /** The {@code xsd:boolean} datatype for boolean values (true/false) */
  IRI BOOLEAN = VocabIriFactory.createIRI(NAMESPACE, "boolean");

  /** The {@code xsd:date} datatype for calendar dates */
  IRI DATE = VocabIriFactory.createIRI(NAMESPACE, "date");

  /** The {@code xsd:dateTime} datatype for date and time with optional timezone */
  IRI DATETIME = VocabIriFactory.createIRI(NAMESPACE, "dateTime");
}
