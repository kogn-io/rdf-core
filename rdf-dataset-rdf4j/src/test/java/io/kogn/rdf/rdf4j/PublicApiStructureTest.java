// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Pins what ADR-0026 states about {@code io.kogn.rdf.rdf4j}: its public classes are final and no
 * public or protected member, inherited ones included, names an RDF4J type, not even as a type
 * argument, a bound, a {@code throws} clause or a supertype.
 */
class PublicApiStructureTest {

  private static final String RDF4J_PACKAGE = "org.eclipse.rdf4j";

  private static final String CLASS_FILE_SUFFIX = ".class";

  @Test
  void scanFindsTheKnownPublicClasses() {
    assertThat(publicClasses()).contains(RDF4JIRI.class, RDF4JLiteral.class, RDF4JBlankNode.class, RDF4JTriple.class,
        RDF4JGraph.class, RDF4JFactory.class);
  }

  @Test
  void publicClassesAreFinal() {
    assertThat(publicClasses()).allMatch(type -> Modifier.isFinal(type.getModifiers()));
  }

  @Test
  void noPublicOrProtectedMemberExposesAnRDF4JType() {
    assertThat(publicClasses().stream().flatMap(type -> leaksOf(type).stream()).toList()).isEmpty();
  }

  /**
   * The public classes of {@code io.kogn.rdf.rdf4j}, found by listing the package directory the
   * main classes were loaded from, so a new public class is covered without touching this test.
   */
  private static List<Class<?>> publicClasses() {
    final Package rdf4jPackage = RDF4JIRI.class.getPackage();
    final Path directory = mainClassesRoot().resolve(rdf4jPackage.getName().replace('.', '/'));
    try (Stream<Path> files = Files.list(directory)) {
      return files.map(file -> file.getFileName().toString())
          .filter(name -> name.endsWith(CLASS_FILE_SUFFIX))
          .map(name -> rdf4jPackage.getName() + "." + name.substring(0, name.length() - CLASS_FILE_SUFFIX.length()))
          .map(PublicApiStructureTest::load)
          .filter(type -> Modifier.isPublic(type.getModifiers()))
          .toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path mainClassesRoot() {
    try {
      final Path root = Path.of(RDF4JIRI.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      assertThat(root).as("main classes are expected in a directory, not a jar").isDirectory();
      return root;
    } catch (final URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Class<?> load(final String className) {
    try {
      return Class.forName(className, false, PublicApiStructureTest.class.getClassLoader());
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Every RDF4J type the class exposes, one line per place it shows up. */
  private static List<String> leaksOf(final Class<?> type) {
    final List<String> leaks = new ArrayList<>();
    final String name = type.getSimpleName();
    supertypes(type).stream()
        .filter(PublicApiStructureTest::namesRDF4J)
        .forEach(leak -> leaks.add(name + " extends/implements " + leak.getTypeName()));
    for (final Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (exposed(constructor.getModifiers())) {
        Stream.of(constructor.getGenericParameterTypes())
            .filter(PublicApiStructureTest::namesRDF4J)
            .forEach(leak -> leaks.add(name + " constructor takes " + leak.getTypeName()));
        Stream.of(constructor.getGenericExceptionTypes())
            .filter(PublicApiStructureTest::namesRDF4J)
            .forEach(leak -> leaks.add(name + " constructor throws " + leak.getTypeName()));
      }
    }
    for (final Method method : exposedMethods(type)) {
      final String member = name + "." + method.getName();
      if (namesRDF4J(method.getGenericReturnType())) {
        leaks.add(member + " returns " + method.getGenericReturnType().getTypeName());
      }
      Stream.of(method.getGenericParameterTypes())
          .filter(PublicApiStructureTest::namesRDF4J)
          .forEach(leak -> leaks.add(member + " takes " + leak.getTypeName()));
      Stream.of(method.getGenericExceptionTypes())
          .filter(PublicApiStructureTest::namesRDF4J)
          .forEach(leak -> leaks.add(member + " throws " + leak.getTypeName()));
      Stream.of(method.getTypeParameters())
          .filter(PublicApiStructureTest::namesRDF4J)
          .forEach(leak -> leaks.add(member + " declares " + leak.getTypeName()));
    }
    for (final Field field : exposedFields(type)) {
      if (namesRDF4J(field.getGenericType())) {
        leaks.add(name + "." + field.getName() + " is " + field.getGenericType().getTypeName());
      }
    }
    Stream.of(type.getTypeParameters())
        .filter(PublicApiStructureTest::namesRDF4J)
        .forEach(leak -> leaks.add(name + " declares " + leak.getTypeName()));
    return leaks;
  }

  /** The generic superclasses and interfaces of the type, transitively. */
  private static Set<Type> supertypes(final Class<?> type) {
    final Set<Type> supertypes = new LinkedHashSet<>();
    final Deque<Class<?>> pending = new ArrayDeque<>(List.of(type));
    while (!pending.isEmpty()) {
      final Class<?> current = pending.pop();
      final List<Type> direct = new ArrayList<>(List.of(current.getGenericInterfaces()));
      Optional.ofNullable(current.getGenericSuperclass()).ifPresent(direct::add);
      for (final Type supertype : direct) {
        if (supertypes.add(supertype)) {
          pending.push(rawClass(supertype));
        }
      }
    }
    return supertypes;
  }

  private static Class<?> rawClass(final Type type) {
    return type instanceof ParameterizedType parameterized ? (Class<?>) parameterized.getRawType() : (Class<?>) type;
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
  private static boolean namesRDF4J(final Type type) {
    return namesRDF4J(type, new HashSet<>());
  }

  /** As {@link #namesRDF4J(Type)}; {@code visited} stops a recursive bound such as {@code T extends Comparable<T>}. */
  private static boolean namesRDF4J(final Type type, final Set<Type> visited) {
    if (!visited.add(type)) {
      return false;
    }
    return switch (type) {
    case Class<?> plain when plain.isArray() -> namesRDF4J(plain.getComponentType(), visited);
    case Class<?> plain -> plain.getName().startsWith(RDF4J_PACKAGE);
    case ParameterizedType parameterized -> namesRDF4J(parameterized.getRawType(), visited)
        || Stream.of(parameterized.getActualTypeArguments()).anyMatch(argument -> namesRDF4J(argument, visited));
    case GenericArrayType array -> namesRDF4J(array.getGenericComponentType(), visited);
    case WildcardType wildcard ->
      Stream.concat(Stream.of(wildcard.getUpperBounds()), Stream.of(wildcard.getLowerBounds()))
          .anyMatch(bound -> namesRDF4J(bound, visited));
    case TypeVariable<?> variable -> Stream.of(variable.getBounds()).anyMatch(bound -> namesRDF4J(bound, visited));
    default -> false;
    };
  }

  @Test
  void typeCheckSeesRDF4JTypeArguments() throws NoSuchMethodException {
    assertThat(namesRDF4J(Probe.class.getDeclaredMethod("leak").getGenericReturnType())).isTrue();
  }

  @Test
  void typeCheckEndsOnARecursiveBound() throws NoSuchMethodException {
    assertThat(namesRDF4J(Probe.class.getDeclaredMethod("recursive").getGenericReturnType())).isFalse();
  }

  @Test
  void leakCheckSeesThrowsClausesAndSupertypes() {
    assertThat(leaksOf(Probe.class)).anyMatch(leak -> leak.contains(" throws "))
        .anyMatch(leak -> leak.contains(" extends/implements "));
  }

  /** A probe that hides RDF4J types where the checks must reach them, plus a recursive bound. */
  private interface Probe extends Comparable<org.eclipse.rdf4j.model.Value> {
    Optional<List<? extends org.eclipse.rdf4j.model.Value>> leak();

    <T extends Comparable<T>> T recursive();

    void fail() throws org.eclipse.rdf4j.repository.RepositoryException;
  }
}
