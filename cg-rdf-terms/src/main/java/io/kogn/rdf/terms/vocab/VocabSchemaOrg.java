// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.terms.vocab;

import io.kogn.rdf.terms.IRI;

/**
 * Schema.org vocabulary terms.
 *
 * <p>Provides constants for common Schema.org properties used across various domains.
 * Schema.org is a collaborative effort to create structured data schemas for the web.
 * This vocabulary is defined at <a href="https://schema.org/">https://schema.org/</a></p>
 *
 * @see <a href="https://schema.org/">Schema.org Documentation</a>
 */
public interface VocabSchemaOrg {
  /** The Schema.org namespace: {@value} */
  String NAMESPACE = "https://schema.org/";

  /**
   * The {@code schema:name} property.
   *
   * <p>The name of the item.</p>
   *
   * @see <a href="https://schema.org/name">schema:name</a>
   */
  IRI NAME = VocabIriFactory.createIRI(NAMESPACE, "name");

  /**
   * The {@code schema:brand} property.
   *
   * <p>The brand(s) associated with a product or service.</p>
   *
   * @see <a href="https://schema.org/brand">schema:brand</a>
   */
  IRI BRAND = VocabIriFactory.createIRI(NAMESPACE, "brand");

  /**
   * The {@code schema:description} property.
   *
   * <p>A description of the item.</p>
   *
   * @see <a href="https://schema.org/description">schema:description</a>
   */
  IRI DESCRIPTION = VocabIriFactory.createIRI(NAMESPACE, "description");

  /**
   * The {@code schema:version} property.
   *
   * <p>The version of the item.</p>
   *
   * @see <a href="https://schema.org/version">schema:version</a>
   */
  IRI VERSION = VocabIriFactory.createIRI(NAMESPACE, "version");

  /**
   * The {@code schema:startDate} property.
   *
   * <p>The start date and time of the item.</p>
   *
   * @see <a href="https://schema.org/startDate">schema:startDate</a>
   */
  IRI START_DATE = VocabIriFactory.createIRI(NAMESPACE, "startDate");

  /**
   * The {@code schema:endDate} property.
   *
   * <p>The end date and time of the item.</p>
   *
   * @see <a href="https://schema.org/endDate">schema:endDate</a>
   */
  IRI END_DATE = VocabIriFactory.createIRI(NAMESPACE, "endDate");
}
