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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The ES distribution the build works with: where Elastic publishes it, where the build unpacks it, and -- once
 * scanned -- an index of the jars it ships, taken from {@code lib} and every {@code modules/*} directory and
 * keyed by artifact id.
 *
 * <p>A library can be shipped by several modules at different versions: ES 9.5.0 ships commons-codec 1.15 in
 * {@code modules/reindex} and 1.19.0 in {@code modules/ingest-attachment}. All copies are kept, and
 * {@link #preferredJarOf} picks between them.
 */
public final class EsDistribution {

  /** A jar the distribution ships. The version is parsed out of the file name. */
  public record BundledJar(String artifactId, String version, Path file) {

    public Path directory() {
      return file.getParent();
    }
  }

  private static final Pattern JAR_NAME_PATTERN = Pattern.compile("^(.+)-(\\d[^/]*)\\.jar$");
  private static final Pattern SHA512_PATTERN = Pattern.compile("[0-9a-fA-F]{128}");
  private static final String LIB_DIR = "lib";
  private static final String MODULES_DIR = "modules";
  private static final String DOWNLOADS_URL =
      "https://artifacts.elastic.co/downloads/elasticsearch";
  private static final String CHECKSUM_SUFFIX = ".sha512";
  private static final int TIMEOUT_MS = 30_000;

  private final Map<String, List<BundledJar>> jarsByArtifactId;

  private EsDistribution(Map<String, List<BundledJar>> jarsByArtifactId) {
    this.jarsByArtifactId = jarsByArtifactId;
  }

  /** The Linux archive Elastic publishes for an ES version. */
  public static String downloadUrl(String esVersion) {
    return downloadUrl(esVersion, DOWNLOADS_URL);
  }

  static String downloadUrl(String esVersion, String downloadsUrl) {
    return downloadsUrl.replaceAll("/+$", "")
        + "/elasticsearch-"
        + esVersion
        + "-linux-x86_64.tar.gz";
  }

  /**
   * Downloads the archive Elastic publishes for {@code esVersion} into {@code buildDir} and returns it,
   * keeping a copy already there.
   *
   * <p>The bytes land in a temporary file and are moved into place only once their SHA-512 matches the
   * checksum Elastic publishes beside the archive. An interrupted or corrupted download therefore leaves
   * nothing a later build could mistake for a complete distribution -- which matters here, because the jars
   * the build mirrors to the libs store are read straight out of what this unpacks.
   */
  public static Path downloadArchiveTo(Path buildDir, String esVersion) {
    return downloadArchiveTo(buildDir, esVersion, DOWNLOADS_URL);
  }

  static Path downloadArchiveTo(Path buildDir, String esVersion, String downloadsUrl) {
    Path archive = archiveIn(buildDir, esVersion);
    if (Files.exists(archive)) {
      return archive;
    }
    String url = downloadUrl(esVersion, downloadsUrl);
    Path partial = null;
    try {
      Files.createDirectories(buildDir);
      partial = Files.createTempFile(buildDir, archive.getFileName().toString(), ".part");
      downloadTo(url, partial);
      verifyChecksum(partial, publishedChecksum(url + CHECKSUM_SUFFIX), url);
      Files.move(partial, archive, StandardCopyOption.ATOMIC_MOVE);
      return archive;
    } catch (IOException e) {
      throw new GradleException("Cannot download " + url + ": " + e, e);
    } finally {
      discard(partial);
    }
  }

  private static void downloadTo(String url, Path target) throws IOException {
    try (InputStream content = open(url)) {
      Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** The checksum file holds the hash and the file name it belongs to, separated by whitespace. */
  private static String publishedChecksum(String url) throws IOException {
    String published;
    try (InputStream content = open(url)) {
      published = new String(content.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
    String checksum = published.split("\\s+", 2)[0];
    if (!SHA512_PATTERN.matcher(checksum).matches()) {
      throw new GradleException("Not a SHA-512 checksum at " + url + ": '" + published + "'");
    }
    return checksum;
  }

  private static void verifyChecksum(Path file, String expected, String url) throws IOException {
    String actual = sha512Of(file);
    if (!expected.equalsIgnoreCase(actual)) {
      throw new GradleException(
          "Checksum mismatch for "
              + url
              + ": expected SHA-512 "
              + expected
              + ", downloaded "
              + actual);
    }
  }

  private static String sha512Of(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-512");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every JVM ships SHA-512", e);
    }
    byte[] buffer = new byte[64 * 1024];
    try (InputStream content = new BufferedInputStream(Files.newInputStream(file))) {
      for (int read = content.read(buffer); read != -1; read = content.read(buffer)) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static InputStream open(String url) throws IOException {
    URLConnection connection = URI.create(url).toURL().openConnection();
    connection.setConnectTimeout(TIMEOUT_MS);
    connection.setReadTimeout(TIMEOUT_MS);
    return connection.getInputStream();
  }

  /** Drops the temporary file, which a successful download has already moved away. */
  private static void discard(Path partial) {
    if (partial != null) {
      try {
        Files.deleteIfExists(partial);
      } catch (IOException e) {
        throw new GradleException("Cannot delete " + partial + ": " + e, e);
      }
    }
  }

  /** Where the build keeps the downloaded archive. */
  public static Path archiveIn(Path buildDir, String esVersion) {
    return buildDir.resolve("es-" + esVersion + ".tar.gz");
  }

  /** Where the archive is unpacked. */
  public static Path unpackDirIn(Path buildDir, String esVersion) {
    return buildDir.resolve("es-" + esVersion);
  }

  /** The distribution itself: the single directory the archive holds, and what {@link #scan} takes. */
  public static Path distributionDirIn(Path buildDir, String esVersion) {
    return unpackDirIn(buildDir, esVersion).resolve("elasticsearch-" + esVersion);
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

  /** Every directory shipping {@code artifactId}, which several modules may do. */
  public Set<Path> directoriesShipping(String artifactId) {
    return jarsOf(artifactId).stream().map(BundledJar::directory).collect(Collectors.toSet());
  }

  /**
   * Picks one copy of {@code artifactId}: from {@code preferredDirs} if it is shipped there, otherwise from
   * {@code lib}, otherwise the newest copy anywhere. Equal versions are ordered by file path, so repeated
   * scans of the same distribution return the same jar.
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
            Comparator.comparing(BundledJar::version, EsVersions.VERSION_COMPARATOR)
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
        .filter(Objects::nonNull);
  }
}
