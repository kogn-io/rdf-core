package io.kogn.rdf.terms.vocab;

import io.kogn.rdf.terms.IRI;

/**
 * RDF vocabulary terms from the W3C RDF specification.
 *
 * <p>Provides constants for core RDF terms such as {@code rdf:type}.
 * This vocabulary is defined at <a href="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
 * http://www.w3.org/1999/02/22-rdf-syntax-ns#</a></p>
 *
 * @see <a href="https://www.w3.org/TR/rdf-schema/">RDF Schema 1.1</a>
 */
public interface VocabRdf {
  /** The RDF namespace: {@value} */
  String NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

  /**
   * The {@code rdf:type} property.
   *
   * <p>Used to state that a resource is an instance of a class.</p>
   *
   * @see <a href="http://www.w3.org/1999/02/22-rdf-syntax-ns#type">rdf:type</a>
   */
  IRI TYPE = VocabIriFactory.createIRI(NAMESPACE, "type");

  /**
   * The {@code rdf:first} property.
   *
   * <p>Relates an RDF list node to its first element.</p>
   *
   * @see <a href="http://www.w3.org/1999/02/22-rdf-syntax-ns#first">rdf:first</a>
   */
  IRI FIRST = VocabIriFactory.createIRI(NAMESPACE, "first");

  /**
   * The {@code rdf:rest} property.
   *
   * <p>Relates an RDF list node to the rest of the list.</p>
   *
   * @see <a href="http://www.w3.org/1999/02/22-rdf-syntax-ns#rest">rdf:rest</a>
   */
  IRI REST = VocabIriFactory.createIRI(NAMESPACE, "rest");

  /**
   * The {@code rdf:nil} resource, marking the end of an RDF list.
   *
   * @see <a href="http://www.w3.org/1999/02/22-rdf-syntax-ns#nil">rdf:nil</a>
   */
  IRI NIL = VocabIriFactory.createIRI(NAMESPACE, "nil");
}
