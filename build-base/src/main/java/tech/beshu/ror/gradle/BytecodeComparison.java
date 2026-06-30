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

package tech.beshu.ror.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pure comparison logic behind the {@code diffBytecode} gradle task (and the repackage bytecode guard): given
 * two fat jars it decides whether their class/resource content is identical, which is what makes deriving one
 * ES version's deliverable from another (the repackage model) safe. Deliberately free of any Gradle types so
 * it can be unit-tested in isolation; the gradle task keeps the property/file wiring and the logging/failure
 * reporting.
 *
 * <p>Two entries are considered equal when their bytes match; a small set of entries that legitimately differ
 * per build (the in-jar build-info and the manifest) is ignored, since those carry the ES version / build
 * metadata rather than compiled behaviour.
 */
public final class BytecodeComparison {

  /** Entries expected to differ per ES version / per build, so excluded from the comparison. */
  public static final Set<String> IGNORED_ENTRIES =
      Set.of("ror-build-info.properties", "META-INF/MANIFEST.MF");

  /** Outcome of comparing two jars: identical, or the sorted list of entries whose bytes diverge. */
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

  private BytecodeComparison() {
  }

  /**
   * Compares the two jars entry-by-entry (ignoring {@link #IGNORED_ENTRIES} and directory entries).
   *
   * @return a {@link Result}; {@link Result#isIdentical()} is {@code true} when every compared entry matches.
   * @throws IOException if either jar cannot be read.
   */
  public static Result compare(File baseJar, File cmpJar) throws IOException {
    TreeMap<String, String> baseDigest = digest(baseJar);
    TreeMap<String, String> cmpDigest = digest(cmpJar);

    List<String> diverging = new ArrayList<>();
    for (String entry : union(baseDigest, cmpDigest)) {
      if (!java.util.Objects.equals(baseDigest.get(entry), cmpDigest.get(entry))) {
        diverging.add(entry);
      }
    }
    diverging.sort(String::compareTo);
    return new Result(diverging);
  }

  private static java.util.TreeSet<String> union(TreeMap<String, String> a, TreeMap<String, String> b) {
    java.util.TreeSet<String> all = new java.util.TreeSet<>(a.keySet());
    all.addAll(b.keySet());
    return all;
  }

  /** Maps each non-ignored, non-directory entry name to the hex SHA-256 of its bytes (sorted by name). */
  private static TreeMap<String, String> digest(File jar) throws IOException {
    TreeMap<String, String> result = new TreeMap<>();
    try (ZipFile zf = new ZipFile(jar)) {
      Enumeration<? extends ZipEntry> entries = zf.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory() || IGNORED_ENTRIES.contains(entry.getName())) {
          continue;
        }
        MessageDigest md = sha256();
        try (InputStream is = zf.getInputStream(entry)) {
          byte[] buf = new byte[64 * 1024];
          int n;
          while ((n = is.read(buf)) != -1) {
            md.update(buf, 0, n);
          }
        }
        result.put(entry.getName(), toHex(md.digest()));
      }
    }
    return result;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
