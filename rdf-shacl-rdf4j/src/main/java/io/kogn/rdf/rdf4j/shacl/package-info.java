/**
 * RDF4J-based implementation of the {@code rdf-shacl} port.
 *
 * <p>{@link io.kogn.rdf.rdf4j.shacl.ShaclValidationRdf4j} implements
 * {@link io.kogn.rdf.shacl.ShaclValidation} by wrapping
 * {@link org.eclipse.rdf4j.sail.shacl.ShaclValidator}. Deliberately independent of the
 * {@code rdf-dataset} / {@code rdf-dataset-rdf4j} modules: SHACL validation is
 * store-independent, so this module depends only on {@code rdf-terms} and
 * {@code rdf-shacl} plus the RDF4J SHACL artifacts it needs.</p>
 *
 * @see io.kogn.rdf.rdf4j.shacl.internal
 */
package io.kogn.rdf.rdf4j.shacl;
