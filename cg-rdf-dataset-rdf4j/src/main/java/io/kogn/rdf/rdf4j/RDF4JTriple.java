package io.kogn.rdf.rdf4j;

import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.Triple;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * RDF4J-based implementation of Triple.
 */
@RequiredArgsConstructor
@EqualsAndHashCode
public class RDF4JTriple implements Triple {

  private final Resource subject;
  private final org.eclipse.rdf4j.model.IRI predicate;
  private final Value object;

  @Override
  public BlankNodeOrIRI getSubject() {
    if (subject instanceof org.eclipse.rdf4j.model.IRI) {
      return new RDF4JIRI((org.eclipse.rdf4j.model.IRI) subject);
    } else if (subject instanceof org.eclipse.rdf4j.model.BNode) {
      return new RDF4JBlankNode((org.eclipse.rdf4j.model.BNode) subject);
    }
    throw new IllegalStateException("Unknown subject type: " + subject.getClass());
  }

  @Override
  public IRI getPredicate() {
    return new RDF4JIRI(predicate);
  }

  @Override
  public RDFTerm getObject() {
    if (object instanceof org.eclipse.rdf4j.model.IRI) {
      return new RDF4JIRI((org.eclipse.rdf4j.model.IRI) object);
    } else if (object instanceof org.eclipse.rdf4j.model.Literal) {
      return new RDF4JLiteral((org.eclipse.rdf4j.model.Literal) object);
    } else if (object instanceof org.eclipse.rdf4j.model.BNode) {
      return new RDF4JBlankNode((org.eclipse.rdf4j.model.BNode) object);
    }
    throw new IllegalStateException("Unknown object type: " + object.getClass());
  }

  /**
   * Converts to RDF4J Statement.
   */
  public Statement toRDF4JStatement() {
    return SimpleValueFactory.getInstance().createStatement(subject, predicate, object);
  }

  @Override
  public String toString() {
    return subject + " " + predicate + " " + object + " .";
  }
}
