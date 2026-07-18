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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pure comparison logic behind the repackage bytecode guard: given the fat jar of a module compiled at its
 * base ES version and the jar of the same source compiled at a target ES version, it decides whether the
 * base-compiled bytecode is safe to ship as the target's deliverable (the repackage model). Deliberately
 * free of any Gradle types so it can be unit-tested in isolation.
 *
 * <p>Classes are compared by RENDERING each to text and comparing the strings -- the recipe used by
 * lightbend-labs/jardiff ({@code AsmTextifyRenderer}), the maintained tool for diffing jars including method
 * bodies. {@link #textify} reads a class with ASM ({@code SKIP_DEBUG | SKIP_FRAMES}), sorts its fields and
 * methods, drops class/member attributes (so Scala 3's {@code TASTY} attribute and the like vanish) and
 * Scala pickle annotations, then renders it through ASM's {@link TraceClassVisitor}. The text is
 * constant-pool-order-independent and free of debug info and stack maps, so two recompilations of the same
 * source against different ES jars render identically UNLESS the emitted behaviour actually differs (a call
 * going from {@code invokevirtual} to {@code invokeinterface}, a changed/added/removed member, ...).
 *
 * <p>The comparison is strict: any real bytecode difference makes a class diverge, which forces that module
 * to compile per base group (see {@code baseEsVersions} / {@code EsVersions.baseFor}). {@code .tasty} pickle
 * entries and per-build metadata ({@link #IGNORED_ENTRIES}) are skipped entirely. If a class cannot be
 * parsed it falls back to a raw byte comparison -- a parse failure can only make the guard over-strict, never
 * miss an unsafe repackage.
 */
public final class BytecodeComparison {

  /** Entries expected to differ per ES version / per build, so excluded from the comparison. */
  public static final Set<String> IGNORED_ENTRIES =
      Set.of("ror-build-info.properties", "META-INF/MANIFEST.MF");

  /** Outcome of comparing two jars: identical, or the sorted list of entries whose content diverges. */
  public static final class Result {
    private final List<String> divergingEntries;

    private Result(List<String> divergingEntries) {
      this.divergingEntries = divergingEntries;
    }

    public boolean isIdentical() {
      return divergingEntries.isEmpty();
    }

    /** Entry names present-but-different or present-in-only-one jar, sorted; empty when identical. */
    public List<String> getDivergingEntries() {
      return divergingEntries;
    }
  }

  private BytecodeComparison() {}

  /**
   * Compares the two jars entry-by-entry. {@code baseJar} is the base-compiled artifact (the one whose
   * bytecode the deliverable reuses); {@code cmpJar} is the same source compiled at the target ES version.
   *
   * @return a {@link Result}; {@link Result#isIdentical()} is {@code true} when reusing the base bytecode is
   *     safe for the target.
   * @throws IOException if either jar cannot be read.
   */
  public static Result compare(File baseJar, File cmpJar) throws IOException {
    Map<String, byte[]> base = readEntries(baseJar);
    Map<String, byte[]> cmp = readEntries(cmpJar);

    List<String> diverging = new ArrayList<>();
    for (String entry : unionKeys(base, cmp)) {
      byte[] b = base.get(entry);
      byte[] c = cmp.get(entry);
      boolean same;
      if (b == null || c == null) {
        same = false; // present in only one jar
      } else if (entry.endsWith(".class")) {
        same = classSafeToReuse(b, c);
      } else {
        same = Arrays.equals(b, c);
      }
      if (!same) {
        diverging.add(entry);
      }
    }
    diverging.sort(String::compareTo);
    return new Result(diverging);
  }

  private static TreeSet<String> unionKeys(Map<String, byte[]> a, Map<String, byte[]> b) {
    TreeSet<String> all = new TreeSet<>(a.keySet());
    all.addAll(b.keySet());
    return all;
  }

  /** Reads every non-ignored, non-directory, non-{@code .tasty} entry into a name -> bytes map. */
  private static Map<String, byte[]> readEntries(File jar) throws IOException {
    Map<String, byte[]> result = new LinkedHashMap<>();
    try (ZipFile zf = new ZipFile(jar)) {
      Enumeration<? extends ZipEntry> entries = zf.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (entry.isDirectory() || IGNORED_ENTRIES.contains(name) || name.endsWith(".tasty")) {
          continue;
        }
        result.put(name, readAll(zf, entry));
      }
    }
    return result;
  }

  private static byte[] readAll(ZipFile zf, ZipEntry entry) throws IOException {
    ByteArrayOutputStream out =
        new ByteArrayOutputStream(
            Math.max(64, (int) Math.min(Math.max(entry.getSize(), 0), 1 << 20)));
    try (InputStream is = zf.getInputStream(entry)) {
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = is.read(buf)) != -1) {
        out.write(buf, 0, n);
      }
    }
    return out.toByteArray();
  }

  /** Two classes are equivalent when they render to the same text; falls back to bytes if unparseable. */
  static boolean classSafeToReuse(byte[] base, byte[] cmp) {
    try {
      return textify(base).equals(textify(cmp));
    } catch (RuntimeException e) {
      return Arrays.equals(base, cmp);
    }
  }

  /**
   * Renders a class to canonical text (the lightbend-labs/jardiff recipe): read without debug info or stack
   * frames, sort members, drop attributes (Scala 3 {@code TASTY} etc.) and Scala pickle annotations, then
   * print through ASM's {@link TraceClassVisitor}.
   */
  static String textify(byte[] classBytes) {
    ClassNode node = new ClassNode();
    new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    node.attrs = null;
    stripScalaPickleAnnotations(node.visibleAnnotations);
    stripScalaPickleAnnotations(node.invisibleAnnotations);
    if (node.fields != null) {
      node.fields.sort(Comparator.comparing((FieldNode f) -> f.name).thenComparing(f -> f.desc));
      for (FieldNode f : node.fields) {
        f.attrs = null;
      }
    }
    if (node.methods != null) {
      node.methods.sort(Comparator.comparing((MethodNode m) -> m.name).thenComparing(m -> m.desc));
      for (MethodNode m : node.methods) {
        m.attrs = null;
      }
    }

    StringWriter text = new StringWriter();
    node.accept(new TraceClassVisitor(new PrintWriter(text)));
    return text.toString();
  }

  private static void stripScalaPickleAnnotations(List<AnnotationNode> annotations) {
    if (annotations != null) {
      annotations.removeIf(
          a ->
              a.desc != null
                  && (a.desc.contains("ScalaSignature") || a.desc.contains("ScalaLongSignature")));
    }
  }
}
