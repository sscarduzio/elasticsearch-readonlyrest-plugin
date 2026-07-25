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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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

  @Test
  void tastyPickleEntriesAreIgnored() throws IOException {
    File a =
        jar(
            "a.jar",
            Map.of(
                "Foo.class",
                bytes("same"),
                "Foo.tasty",
                bytes("pickle-compiled-against-es-7.4.0")));
    File b =
        jar(
            "b.jar",
            Map.of(
                "Foo.class",
                bytes("same"),
                "Foo.tasty",
                bytes("pickle-compiled-against-es-7.6.2")));
    assertTrue(BytecodeComparison.compare(a, b).isIdentical());
  }

  @Test
  void identicalClassesAreIdentical() throws IOException {
    File a = jar("a.jar", Map.of("Foo.class", classWithForwarder("m", "()Z")));
    File b = jar("b.jar", Map.of("Foo.class", classWithForwarder("m", "()Z")));
    assertTrue(BytecodeComparison.compare(a, b).isIdentical());
  }

  @Test
  void methodPresentInOnlyOneClassDiverges() throws IOException {
    // Strict comparison: any method asymmetry -- including a compiler-generated forwarder that
    // appears
    // only in the target compile when an ES interface gains a default -- is flagged, in either
    // direction. Such modules compile per base group (baseEsVersions).
    File withMethod =
        jar("with.jar", Map.of("Foo.class", classWithForwarder("supportsBulkContent", "()Z")));
    File without = jar("without.jar", Map.of("Foo.class", emptyClass()));
    assertFalse(BytecodeComparison.compare(withMethod, without).isIdentical());
    assertEquals(
        List.of("Foo.class"),
        BytecodeComparison.compare(without, withMethod).getDivergingEntries());
  }

  @Test
  void sameMethodWithDifferentBodyDiverges() throws IOException {
    // Same signature, different implementation (concrete forwarder vs abstract) -> flagged.
    File a = jar("a.jar", Map.of("Foo.class", classWithForwarder("m", "()Z")));
    File b = jar("b.jar", Map.of("Foo.class", classWithAbstractMethod("m", "()Z")));
    assertFalse(BytecodeComparison.compare(a, b).isIdentical());
  }

  @Test
  void invokeVirtualVsInterfaceOnSameCallDiverges() throws IOException {
    // The es74x case: an ES type changed from class to interface, so the same call compiles to
    // invokevirtual vs invokeinterface -- a real, fatal difference the text comparison must catch.
    File asClass = jar("a.jar", Map.of("Foo.class", classCalling("run", Opcodes.INVOKEVIRTUAL)));
    File asIface = jar("b.jar", Map.of("Foo.class", classCalling("run", Opcodes.INVOKEINTERFACE)));
    assertFalse(BytecodeComparison.compare(asClass, asIface).isIdentical());
  }

  @Test
  void memberDeclarationOrderDoesNotMatter() throws IOException {
    // Fields and methods are sorted before rendering, so source order is irrelevant.
    File a = jar("a.jar", Map.of("Foo.class", classWithTwoMethods("alpha", "beta")));
    File b = jar("b.jar", Map.of("Foo.class", classWithTwoMethods("beta", "alpha")));
    assertTrue(BytecodeComparison.compare(a, b).isIdentical());
  }

  // --- ASM classfile builders (all named "Foo", super java/lang/Object)
  // --------------------------------

  private static byte[] emptyClass() {
    return clazz(cn -> {});
  }

  /** One public method {@code name+desc} whose body forwards to a same-signature super method. */
  private static byte[] classWithForwarder(String name, String desc) {
    return clazz(
        cn -> {
          MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
          m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
          m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "Owner", name, desc, false));
          m.instructions.add(new InsnNode(returnOpcodeFor(desc)));
          m.maxStack = 1;
          m.maxLocals = 1;
          cn.methods.add(m);
        });
  }

  private static byte[] classWithAbstractMethod(String name, String desc) {
    return clazz(
        cn -> cn.methods.add(new MethodNode(Opcodes.ACC_ABSTRACT, name, desc, null, null)));
  }

  /** Two abstract methods added in the given order (to prove ordering is normalized away). */
  private static byte[] classWithTwoMethods(String first, String second) {
    return clazz(
        cn -> {
          cn.methods.add(new MethodNode(Opcodes.ACC_ABSTRACT, first, "()V", null, null));
          cn.methods.add(new MethodNode(Opcodes.ACC_ABSTRACT, second, "()V", null, null));
        });
  }

  /** One public method calling {@code Target.foo()V} via the given invoke opcode. */
  private static byte[] classCalling(String methodName, int invokeOpcode) {
    boolean itf = invokeOpcode == Opcodes.INVOKEINTERFACE;
    return clazz(
        cn -> {
          MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
          m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
          m.instructions.add(new MethodInsnNode(invokeOpcode, "Target", "foo", "()V", itf));
          m.instructions.add(new InsnNode(Opcodes.RETURN));
          m.maxStack = 1;
          m.maxLocals = 1;
          cn.methods.add(m);
        });
  }

  private static byte[] clazz(Consumer<ClassNode> configure) {
    ClassNode cn = new ClassNode();
    cn.version = Opcodes.V11;
    cn.access = Opcodes.ACC_PUBLIC;
    cn.name = "Foo";
    cn.superName = "java/lang/Object";
    configure.accept(cn);
    ClassWriter cw = new ClassWriter(0);
    cn.accept(cw);
    return cw.toByteArray();
  }

  private static int returnOpcodeFor(String desc) {
    switch (desc.charAt(desc.indexOf(')') + 1)) {
      case 'V':
        return Opcodes.RETURN;
      case 'J':
        return Opcodes.LRETURN;
      case 'F':
        return Opcodes.FRETURN;
      case 'D':
        return Opcodes.DRETURN;
      case 'L':
      case '[':
        return Opcodes.ARETURN;
      default:
        return Opcodes.IRETURN; // Z, B, C, S, I
    }
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
