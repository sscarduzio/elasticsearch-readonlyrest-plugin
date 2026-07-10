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

package tech.beshu.ror.gradle.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class BytecodeComparisonTest {

  @TempDir Path tempDir;

  @Test
  void identicalJarsAreIdentical() throws IOException {
    File a = jar("a.jar", Map.of("Foo.class", bytes("v1")));
    File b = jar("b.jar", Map.of("Foo.class", bytes("v1")));
    BytecodeComparison.Result result = BytecodeComparison.compare(a, b);
    assertTrue(result.isIdentical());
    assertTrue(result.getDivergingEntries().isEmpty());
  }

  @Test
  void differentContentForSameEntryDiverges() throws IOException {
    File a = jar("a.jar", Map.of("Foo.class", bytes("v1")));
    File b = jar("b.jar", Map.of("Foo.class", bytes("v2")));
    BytecodeComparison.Result result = BytecodeComparison.compare(a, b);
    assertFalse(result.isIdentical());
    assertEquals(List.of("Foo.class"), result.getDivergingEntries());
  }

  @Test
  void entryPresentInOnlyOneJarDiverges() throws IOException {
    File a = jar("a.jar", Map.of("Foo.class", bytes("x"), "Bar.class", bytes("y")));
    File b = jar("b.jar", Map.of("Foo.class", bytes("x")));
    BytecodeComparison.Result result = BytecodeComparison.compare(a, b);
    assertFalse(result.isIdentical());
    assertEquals(List.of("Bar.class"), result.getDivergingEntries());
  }

  @Test
  void ignoredEntriesWithDifferentContentAreNotDiverging() throws IOException {
    File a =
        jar(
            "a.jar",
            Map.of(
                "Foo.class", bytes("same"),
                "ror-build-info.properties", bytes("es=8.18.0"),
                "META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\n")));
    File b =
        jar(
            "b.jar",
            Map.of(
                "Foo.class", bytes("same"),
                "ror-build-info.properties", bytes("es=8.19.0"),
                "META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\nBuild: 2\n")));
    BytecodeComparison.Result result = BytecodeComparison.compare(a, b);
    assertTrue(result.isIdentical());
  }

  @Test
  void directoryEntriesAreNotCompared() throws IOException {
    File a = jar("a.jar", Map.of("Foo.class", bytes("x")));
    File b = tempDir.resolve("b.jar").toFile();
    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(b))) {
      zos.putNextEntry(new ZipEntry("tech/beshu/")); // directory entry — name ends with /
      zos.closeEntry();
      zos.putNextEntry(new ZipEntry("Foo.class"));
      zos.write(bytes("x"));
      zos.closeEntry();
    }
    BytecodeComparison.Result result = BytecodeComparison.compare(a, b);
    assertTrue(result.isIdentical());
  }

  @Test
  void divergingEntriesAreSortedAlphabetically() throws IOException {
    File a = jar("a.jar", Map.of("Z.class", bytes("1"), "A.class", bytes("1")));
    File b = jar("b.jar", Map.of("Z.class", bytes("2"), "A.class", bytes("2")));
    BytecodeComparison.Result result = BytecodeComparison.compare(a, b);
    assertEquals(List.of("A.class", "Z.class"), result.getDivergingEntries());
  }

  @Test
  void emptyJarsAreIdentical() throws IOException {
    File a = jar("a.jar", Map.of());
    File b = jar("b.jar", Map.of());
    assertTrue(BytecodeComparison.compare(a, b).isIdentical());
  }

  private File jar(String name, Map<String, byte[]> entries) throws IOException {
    File f = tempDir.resolve(name).toFile();
    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(f))) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        zos.putNextEntry(new ZipEntry(e.getKey()));
        zos.write(e.getValue());
        zos.closeEntry();
      }
    }
    return f;
  }

  private static byte[] bytes(String s) {
    return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
