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

import org.gradle.api.GradleException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Indexes the jars an extracted ES distribution ships -- {@code lib} plus every {@code modules/*} directory --
 * so the artifacts we mirror to the libs store, and the versions the generated POMs declare, come from what
 * Elastic actually ships instead of from a hand-maintained list.
 *
 * <p>The same library can be shipped by several modules at different versions: ES 9.5.0 carries commons-codec
 * 1.15 next to the rest client in {@code modules/reindex} and 1.19.0 under {@code modules/ingest-attachment}.
 * Every copy is therefore kept, and {@link #preferredJarOf} decides which one a given artifact is paired with.
 */
public final class EsDistribution {

  /** A jar shipped by the distribution, with the version parsed out of its file name. */
  public record BundledJar(String artifactId, String version, Path file) {

    public Path directory() {
      return file.getParent();
    }
  }

  private static final Pattern JAR_NAME_PATTERN = Pattern.compile("^(.+)-(\\d[^/]*)\\.jar$");
  private static final String LIB_DIR = "lib";
  private static final String MODULES_DIR = "modules";

  private final Map<String, List<BundledJar>> jarsByArtifactId;

  private EsDistribution(Map<String, List<BundledJar>> jarsByArtifactId) {
    this.jarsByArtifactId = jarsByArtifactId;
  }

  /** Scans {@code lib} and every {@code modules/*} directory of an extracted distribution. */
  public static EsDistribution scan(Path extractedDir) {
    if (!Files.isDirectory(extractedDir)) {
      throw new GradleException("Not an extracted ES distribution: " + extractedDir);
    }
    List<Path> directories = new ArrayList<>();
    directories.add(extractedDir.resolve(LIB_DIR));
    directories.addAll(subDirectoriesOf(extractedDir.resolve(MODULES_DIR)));

    Map<String, List<BundledJar>> index =
        directories.stream()
            .flatMap(EsDistribution::jarsIn)
            .collect(Collectors.groupingBy(BundledJar::artifactId));
    if (index.isEmpty()) {
      throw new GradleException(
          "No jars found in " + extractedDir + "/" + LIB_DIR + " or its modules");
    }
    return new EsDistribution(index);
  }

  /** Every copy of {@code artifactId} the distribution ships, in no particular order. */
  public List<BundledJar> jarsOf(String artifactId) {
    return jarsByArtifactId.getOrDefault(artifactId, List.of());
  }

  /** The directories shipping {@code artifactId}; an artifact may be bundled by several modules. */
  public Set<Path> directoriesShipping(String artifactId) {
    return jarsOf(artifactId).stream().map(BundledJar::directory).collect(Collectors.toSet());
  }

  /**
   * Which copy of a bundled library to believe: one shipped alongside the artifact whose POM is being
   * generated, then the one in {@code lib}, and only then the newest one found anywhere. Ties are broken on
   * the file path so that a given distribution always yields the same answer.
   */
  public Optional<BundledJar> preferredJarOf(String artifactId, Collection<Path> preferredDirs) {
    List<BundledJar> candidates = jarsOf(artifactId);
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    List<BundledJar> alongsideTheArtifact =
        candidates.stream().filter(jar -> preferredDirs.contains(jar.directory())).toList();
    if (!alongsideTheArtifact.isEmpty()) {
      return Optional.of(newestOf(alongsideTheArtifact));
    }
    List<BundledJar> inLibDir =
        candidates.stream()
            .filter(jar -> LIB_DIR.equals(jar.directory().getFileName().toString()))
            .toList();
    return Optional.of(newestOf(inLibDir.isEmpty() ? candidates : inLibDir));
  }

  private static BundledJar newestOf(List<BundledJar> candidates) {
    return candidates.stream()
        .max(
            Comparator.comparing(BundledJar::version, BUNDLED_VERSION_COMPARATOR)
                .thenComparing(jar -> jar.file().toString()))
        .orElseThrow();
  }

  private static List<Path> subDirectoriesOf(Path dir) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.filter(Files::isDirectory).sorted().toList();
    } catch (IOException e) {
      throw new GradleException("Cannot list " + dir + ": " + e.getMessage(), e);
    }
  }

  private static Stream<BundledJar> jarsIn(Path dir) {
    File[] files = dir.toFile().listFiles();
    if (files == null) {
      return Stream.empty();
    }
    return Stream.of(files)
        .filter(file -> file.getName().endsWith(".jar"))
        .map(
            file -> {
              Matcher matcher = JAR_NAME_PATTERN.matcher(file.getName());
              return matcher.matches()
                  ? new BundledJar(matcher.group(1), matcher.group(2), file.toPath())
                  : null;
            })
        .filter(jar -> jar != null);
  }

  /**
   * Orders the versions of BUNDLED libraries, which are not all ES versions and so cannot go through
   * {@link EsVersions#VERSION_COMPARATOR} (netty's {@code 4.1.135.Final} has a non-numeric segment that one
   * rejects). Numeric segments compare numerically, anything else lexicographically.
   */
  public static final Comparator<String> BUNDLED_VERSION_COMPARATOR =
      EsDistribution::compareBundledVersions;

  private static int compareBundledVersions(String left, String right) {
    String[] leftParts = left.split("\\.");
    String[] rightParts = right.split("\\.");
    for (int i = 0; i < Math.max(leftParts.length, rightParts.length); i++) {
      String leftPart = i < leftParts.length ? leftParts[i] : "";
      String rightPart = i < rightParts.length ? rightParts[i] : "";
      OptionalInt leftNumber = asNumber(leftPart);
      OptionalInt rightNumber = asNumber(rightPart);
      int comparison =
          leftNumber.isPresent() && rightNumber.isPresent()
              ? Integer.compare(leftNumber.getAsInt(), rightNumber.getAsInt())
              : leftPart.compareTo(rightPart);
      if (comparison != 0) {
        return comparison;
      }
    }
    return 0;
  }

  private static OptionalInt asNumber(String part) {
    try {
      return OptionalInt.of(Integer.parseInt(part));
    } catch (NumberFormatException e) {
      return OptionalInt.empty();
    }
  }
}
