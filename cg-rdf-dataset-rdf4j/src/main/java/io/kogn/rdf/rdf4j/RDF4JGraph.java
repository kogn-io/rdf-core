package io.kogn.rdf.rdf4j;

import java.io.StringWriter;
import java.util.stream.Stream;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.Triple;

/**
 * RDF4J-based implementation of Graph.
 */
public class RDF4JGraph implements Graph {

  private final Model model;

  public RDF4JGraph() {
    this.model = new LinkedHashModel();
  }

  public RDF4JGraph(Model model) {
    this.model = model;
  }

  @Override
  public void add(Triple triple) {
    if (triple instanceof RDF4JTriple rdf4jTriple) {
      model.add(rdf4jTriple.toRDF4JStatement());
    } else {
      add(triple.getSubject(), triple.getPredicate(), triple.getObject());
    }
  }

  @Override
  public void add(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
    org.eclipse.rdf4j.model.Resource rdf4jSubject = RDF4JConverters.toRDF4JResource(subject);
    org.eclipse.rdf4j.model.IRI rdf4jPredicate = RDF4JConverters.toRDF4JIRI(predicate);
    Value rdf4jObject = RDF4JConverters.toRDF4JValue(object);

    model.add(rdf4jSubject, rdf4jPredicate, rdf4jObject);
  }

  @Override
  public void remove(Triple triple) {
    if (triple instanceof RDF4JTriple rdf4jTriple) {
      model.remove(rdf4jTriple.toRDF4JStatement());
    } else {
      org.eclipse.rdf4j.model.Resource rdf4jSubject = RDF4JConverters.toRDF4JResource(triple.getSubject());
      org.eclipse.rdf4j.model.IRI rdf4jPredicate = RDF4JConverters.toRDF4JIRI(triple.getPredicate());
      Value rdf4jObject = RDF4JConverters.toRDF4JValue(triple.getObject());
      model.remove(rdf4jSubject, rdf4jPredicate, rdf4jObject);
    }
  }

  @Override
  public boolean contains(Triple triple) {
    if (triple instanceof RDF4JTriple rdf4jTriple) {
      return model.contains(rdf4jTriple.toRDF4JStatement());
    }
    org.eclipse.rdf4j.model.Resource rdf4jSubject = RDF4JConverters.toRDF4JResource(triple.getSubject());
    org.eclipse.rdf4j.model.IRI rdf4jPredicate = RDF4JConverters.toRDF4JIRI(triple.getPredicate());
    Value rdf4jObject = RDF4JConverters.toRDF4JValue(triple.getObject());
    return model.contains(rdf4jSubject, rdf4jPredicate, rdf4jObject);
  }

  @Override
  public long size() {
    return model.size();
  }

  @Override
  public Stream<Triple> stream() {
    return model.stream().map(stmt -> new RDF4JTriple(stmt.getSubject(), stmt.getPredicate(), stmt.getObject()));
  }

  @Override
  public Stream<Triple> stream(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
    // Convert our API types to RDF4J types, using null for wildcards
    org.eclipse.rdf4j.model.Resource rdf4jSubject = subject != null ? RDF4JConverters.toRDF4JResource(subject) : null;
    org.eclipse.rdf4j.model.IRI rdf4jPredicate = predicate != null ? RDF4JConverters.toRDF4JIRI(predicate) : null;
    Value rdf4jObject = object != null ? RDF4JConverters.toRDF4JValue(object) : null;

    // Use RDF4J Model's filter method for pattern matching
    return model.filter(rdf4jSubject, rdf4jPredicate, rdf4jObject)
        .stream()
        .map(stmt -> new RDF4JTriple(stmt.getSubject(), stmt.getPredicate(), stmt.getObject()));
  }

  @Override
  public void clear() {
    model.clear();
  }

  /**
   * Returns the wrapped RDF4J Model.
   */
  public Model getRDF4JModel() {
    return model;
  }

  @Override
  public boolean isEmpty() {
    return model.isEmpty();
  }

  @Override
  public String toString() {
    StringWriter sw = new StringWriter();
    Rio.write(model, sw, RDFFormat.TURTLE);
    return "RDF4JGraph [" + sw.toString() + "]";
  }
}
