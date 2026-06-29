package io.kogn.rdf.terms.vocab;

import io.kogn.rdf.terms.IRI;

/**
 * RDFS (RDF Schema) vocabulary terms.
 *
 * <p>Provides constants for commonly used RDFS properties.</p>
 *
 * <p>This vocabulary is defined at <a href="http://www.w3.org/2000/01/rdf-schema#">
 * http://www.w3.org/2000/01/rdf-schema#</a></p>
 */
public interface VocabRdfs {
  /** The RDFS namespace: {@value} */
  String NAMESPACE = "http://www.w3.org/2000/01/rdf-schema#";

  /**
   * The {@code rdfs:label} property.
   */
  IRI LABEL = VocabIriFactory.createIRI(NAMESPACE, "label");

  /**
   * The {@code rdfs:comment} property.
   */
  IRI COMMENT = VocabIriFactory.createIRI(NAMESPACE, "comment");
}
