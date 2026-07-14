/**
 * Internal RDF4J adapter glue — <strong>not public API</strong>.
 *
 * <p>Types in this package convert between the {@code io.kogn.rdf.terms} API and the
 * RDF4J value model. They may change or move without notice; consumers outside this
 * library must not depend on them.</p>
 *
 * <p>Deliberately independent of {@code io.kogn.rdf.rdf4j.internal.RDF4JConverters}
 * (which lives in {@code rdf-dataset-rdf4j}): this module must not depend on the
 * dataset adapter, so it reimplements the small subset of term conversion it needs.</p>
 *
 * @see io.kogn.rdf.rdf4j.shacl.internal.GraphModelConverter
 */
package io.kogn.rdf.rdf4j.shacl.internal;
