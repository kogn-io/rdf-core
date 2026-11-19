// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Pins what ADR-0026 states about {@code io.kogn.rdf.rdf4j}: its public classes are final and no
 * public or protected member exposes an RDF4J type.
 */
class PublicApiStructureTest {

  private static final String RDF4J_PACKAGE = "org.eclipse.rdf4j";

  private static final List<Class<?>> PUBLIC_CLASSES = List.of(RDF4JIRI.class, RDF4JLiteral.class, RDF4JBlankNode.class,
      RDF4JTriple.class, RDF4JGraph.class, RDF4JFactory.class);

  @Test
  void publicClassesAreFinal() {
    assertThat(PUBLIC_CLASSES).allMatch(type -> Modifier.isFinal(type.getModifiers()));
  }

  @Test
  void noPublicOrProtectedMemberExposesAnRdf4jType() {
    final List<String> leaks = new ArrayList<>();
    for (final Class<?> type : PUBLIC_CLASSES) {
      for (final Constructor<?> constructor : type.getDeclaredConstructors()) {
        if (exposed(constructor.getModifiers())) {
          Stream.of(constructor.getParameterTypes())
              .filter(PublicApiStructureTest::isRdf4j)
              .forEach(leak -> leaks.add(type.getSimpleName() + " constructor: " + leak.getName()));
        }
      }
      for (final Method method : type.getMethods()) {
        if (isRdf4j(method.getReturnType())) {
          leaks.add(type.getSimpleName() + "." + method.getName() + " returns " + method.getReturnType().getName());
        }
        Stream.of(method.getParameterTypes())
            .filter(PublicApiStructureTest::isRdf4j)
            .forEach(leak -> leaks.add(type.getSimpleName() + "." + method.getName() + " takes " + leak.getName()));
      }
    }
    assertThat(leaks).isEmpty();
  }

  private static boolean exposed(final int modifiers) {
    return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
  }

  private static boolean isRdf4j(final Class<?> type) {
    return type.getName().startsWith(RDF4J_PACKAGE);
  }
}
