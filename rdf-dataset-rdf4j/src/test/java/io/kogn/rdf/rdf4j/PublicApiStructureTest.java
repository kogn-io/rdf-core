// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Pins what ADR-0026 states about {@code io.kogn.rdf.rdf4j}: its public classes are final and no
 * public or protected member, inherited ones included, names an RDF4J type, not even as a type
 * argument.
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
          Stream.of(constructor.getGenericParameterTypes())
              .filter(PublicApiStructureTest::namesRdf4j)
              .forEach(leak -> leaks.add(type.getSimpleName() + " constructor: " + leak.getTypeName()));
        }
      }
      for (final Method method : exposedMethods(type)) {
        if (namesRdf4j(method.getGenericReturnType())) {
          leaks.add(type.getSimpleName() + "." + method.getName() + " returns "
              + method.getGenericReturnType().getTypeName());
        }
        Stream.of(method.getGenericParameterTypes())
            .filter(PublicApiStructureTest::namesRdf4j)
            .forEach(leak -> leaks.add(type.getSimpleName() + "." + method.getName() + " takes " + leak.getTypeName()));
      }
      for (final Field field : exposedFields(type)) {
        if (namesRdf4j(field.getGenericType())) {
          leaks.add(type.getSimpleName() + "." + field.getName() + " is " + field.getGenericType().getTypeName());
        }
      }
    }
    assertThat(leaks).isEmpty();
  }

  /** The public methods, inherited ones included, plus the protected ones of the class and its superclasses. */
  private static Set<Method> exposedMethods(final Class<?> type) {
    final Set<Method> methods = new LinkedHashSet<>(List.of(type.getMethods()));
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      Stream.of(current.getDeclaredMethods()).filter(method -> exposed(method.getModifiers())).forEach(methods::add);
    }
    return methods;
  }

  /** The public fields, inherited ones included, plus the protected ones of the class and its superclasses. */
  private static Set<Field> exposedFields(final Class<?> type) {
    final Set<Field> fields = new LinkedHashSet<>(List.of(type.getFields()));
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      Stream.of(current.getDeclaredFields()).filter(field -> exposed(field.getModifiers())).forEach(fields::add);
    }
    return fields;
  }

  private static boolean exposed(final int modifiers) {
    return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
  }

  /** Whether the type, or any type argument, bound or component type within it, is an RDF4J type. */
  private static boolean namesRdf4j(final Type type) {
    return switch (type) {
    case Class<?> plain when plain.isArray() -> namesRdf4j(plain.getComponentType());
    case Class<?> plain -> plain.getName().startsWith(RDF4J_PACKAGE);
    case ParameterizedType parameterized -> namesRdf4j(parameterized.getRawType())
        || Stream.of(parameterized.getActualTypeArguments()).anyMatch(PublicApiStructureTest::namesRdf4j);
    case GenericArrayType array -> namesRdf4j(array.getGenericComponentType());
    case WildcardType wildcard ->
      Stream.concat(Stream.of(wildcard.getUpperBounds()), Stream.of(wildcard.getLowerBounds()))
          .anyMatch(PublicApiStructureTest::namesRdf4j);
    case TypeVariable<?> variable -> Stream.of(variable.getBounds()).anyMatch(PublicApiStructureTest::namesRdf4j);
    default -> false;
    };
  }

  @Test
  void typeCheckSeesRdf4jTypeArguments() throws NoSuchMethodException {
    assertThat(namesRdf4j(Probe.class.getDeclaredMethod("leak").getGenericReturnType())).isTrue();
  }

  /** A probe that hides an RDF4J type in a type argument, to prove the check reaches it. */
  private interface Probe {
    Optional<List<? extends org.eclipse.rdf4j.model.Value>> leak();
  }
}
