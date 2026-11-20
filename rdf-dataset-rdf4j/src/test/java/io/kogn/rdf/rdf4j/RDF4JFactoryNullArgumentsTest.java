// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;

/**
 * Every factory method of the RDF4J backend reports a {@code null} argument as the
 * {@link NullPointerException} the {@link RDF} contract names.
 */
class RDF4JFactoryNullArgumentsTest {

  private static final RDF RDF4J = new RDF4JFactory();
  private static final IRI IRI = RDF4J.createIRI("https://example.org/s");

  static Stream<Arguments> nullArguments() {
    return Stream.of(Arguments.of("createIRI(null)", (Executable) () -> RDF4J.createIRI(null)),
        Arguments.of("createLiteral(null)", (Executable) () -> RDF4J.createLiteral(null)),
        Arguments.of("createLiteral(null, tag)", (Executable) () -> RDF4J.createLiteral(null, "en")),
        Arguments.of("createLiteral(lex, (String) null)", (Executable) () -> RDF4J.createLiteral("v", (String) null)),
        Arguments.of("createLiteral(null, datatype)", (Executable) () -> RDF4J.createLiteral(null, IRI)),
        Arguments.of("createLiteral(lex, (IRI) null)", (Executable) () -> RDF4J.createLiteral("v", (IRI) null)),
        Arguments.of("createBlankNode(null)", (Executable) () -> RDF4J.createBlankNode(null)),
        Arguments.of("createTriple(null, p, o)", (Executable) () -> RDF4J.createTriple(null, IRI, IRI)),
        Arguments.of("createTriple(s, null, o)", (Executable) () -> RDF4J.createTriple(IRI, null, IRI)),
        Arguments.of("createTriple(s, p, null)", (Executable) () -> RDF4J.createTriple(IRI, IRI, null)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nullArguments")
  void nullArgument_isNullPointerException(final String call, final Executable factoryCall) {
    assertThatThrownBy(factoryCall::execute).isExactlyInstanceOf(NullPointerException.class);
  }
}
