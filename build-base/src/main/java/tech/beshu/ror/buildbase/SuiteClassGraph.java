/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.buildbase;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds the classes that are subtypes of a given type — extending it, implementing it, or mixing in
 * a Scala trait, at any depth — by READING class files rather than loading them. Loading would
 * resolve the hierarchy against the runtime classpath, which does not reliably exist at Gradle
 * configuration time, because a project dependency's jar is only built later.
 *
 * <p>Sound for a supertype declared inside the scanned tree: nothing outside that tree can be a
 * subtype of it, so class files elsewhere cannot affect the answer.
 */
public final class SuiteClassGraph {

  private SuiteClassGraph() {}

  /** The subset of {@code candidates} (binary names) that are subtypes of {@code supertype}. */
  public static Set<String> subtypesOf(
      String supertype, Collection<Path> classDirs, Collection<String> candidates) {
    Set<String> allSubtypes = allSubtypesOf(supertype, directSubtypesBySupertypeIn(classDirs));
    return candidates.stream().filter(allSubtypes::contains).collect(Collectors.toSet());
  }

  /** One downward sweep from the supertype; everything reached is a subtype of it. */
  private static Set<String> allSubtypesOf(String supertype, Map<String, List<String>> subtypes) {
    Set<String> reached = new HashSet<>();
    Deque<String> pending = new ArrayDeque<>(List.of(supertype));
    while (!pending.isEmpty()) {
      for (String subtype : subtypes.getOrDefault(pending.pop(), List.of())) {
        if (reached.add(subtype)) {
          pending.push(subtype);
        }
      }
    }
    return reached;
  }

  /**
   * The hierarchy inverted: each type mapped to the types that declare it as a DIRECT supertype.
   * Transitive subtypes come from sweeping this map, not from the map itself.
   */
  private static Map<String, List<String>> directSubtypesBySupertypeIn(Collection<Path> classDirs) {
    return classDirs.stream()
        .filter(Files::isDirectory)
        .flatMap(SuiteClassGraph::classFilesIn)
        .map(SuiteClassGraph::read)
        .flatMap(
            node ->
                declaredSupertypesOf(node).stream()
                    .map(declared -> Map.entry(declared, binaryName(node.name))))
        .collect(
            Collectors.groupingBy(
                Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
  }

  private static Stream<Path> classFilesIn(Path dir) {
    try {
      // flatMap closes each mapped stream once its contents are consumed, so the walk is released.
      return Files.walk(dir).filter(file -> file.getFileName().toString().endsWith(".class"));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot walk " + dir, e);
    }
  }

  private static ClassNode read(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      ClassNode node = new ClassNode();
      // Only the header (extends + implements) is needed; the rest of the class file is skipped.
      new ClassReader(in)
          .accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      return node;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  private static List<String> declaredSupertypesOf(ClassNode node) {
    // superName is null for java.lang.Object only.
    return Stream.concat(Stream.ofNullable(node.superName), node.interfaces.stream())
        .map(SuiteClassGraph::binaryName)
        .collect(Collectors.toList());
  }

  private static String binaryName(String internalName) {
    return internalName.replace('/', '.');
  }
}
