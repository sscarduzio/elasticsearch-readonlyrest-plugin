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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
 * <p>The same library can be shipped by several modules, and not always at the same version -- one module can
 * bundle a newer copy than another does. Every copy is kept, and picking between them is left to the caller:
 * {@link #jarOf} selects one by version, {@link #classpathDirOf} by the directory ES loads the artifact from.
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

  private final Map<String, List<BundledJar>> jarsByArtifactId;
  private final Map<Path, List<BundledJar>> jarsByDirectory;

  private EsDistribution(
      Map<String, List<BundledJar>> jarsByArtifactId, Map<Path, List<BundledJar>> jarsByDirectory) {
    this.jarsByArtifactId = jarsByArtifactId;
    this.jarsByDirectory = jarsByDirectory;
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
   * reusing a copy already there.
   *
   * <p>Whichever of the two it returns has been checked against the SHA-512 Elastic publishes beside the
   * archive: a download goes to a temporary file and is moved into place only once it matches, and a copy
   * already in {@code buildDir} is hashed before being reused, since the build dir is a plain directory and
   * an earlier build -- or anything else -- may have left something else there. That the archive is what
   * Elastic shipped matters here, because the jars mirrored to the libs store are read out of what it unpacks
   * into.
   */
  public static Path downloadArchiveTo(Path buildDir, String esVersion) {
    return downloadArchiveTo(buildDir, esVersion, DOWNLOADS_URL);
  }

  static Path downloadArchiveTo(Path buildDir, String esVersion, String downloadsUrl) {
    Path archive = archiveIn(buildDir, esVersion);
    String url = downloadUrl(esVersion, downloadsUrl);
    Path partial = null;
    try {
      String checksum = publishedChecksum(url + CHECKSUM_SUFFIX);
      if (Files.exists(archive)) {
        verifyChecksum(archive, checksum, url);
        return archive;
      }
      Files.createDirectories(buildDir);
      partial = Files.createTempFile(buildDir, archive.getFileName().toString(), ".part");
      downloadTo(url, partial);
      verifyChecksum(partial, checksum, url);
      Files.move(partial, archive, StandardCopyOption.ATOMIC_MOVE);
      return archive;
    } catch (IOException e) {
      throw new GradleException("Cannot download " + url + ": " + e, e);
    } finally {
      discard(partial);
    }
  }

  private static void downloadTo(String url, Path target) throws IOException {
    try (InputStream content = Downloads.open(url)) {
      Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** The checksum file holds the hash and the file name it belongs to, separated by whitespace. */
  private static String publishedChecksum(String url) throws IOException {
    String published;
    try (InputStream content = Downloads.open(url)) {
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
          "Checksum mismatch: "
              + file
              + " is not what "
              + url
              + " publishes (expected SHA-512 "
              + expected
              + ", got "
              + actual
              + "). Delete it to download the archive again.");
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

    List<BundledJar> jars = directories.stream().flatMap(EsDistribution::jarsOfDirectory).toList();
    if (jars.isEmpty()) {
      throw new GradleException(
          "No jars found in " + extractedDir + "/" + LIB_DIR + " or its modules");
    }
    return new EsDistribution(
        jars.stream().collect(Collectors.groupingBy(BundledJar::artifactId)),
        jars.stream()
            .collect(
                Collectors.groupingBy(
                    BundledJar::directory, LinkedHashMap::new, Collectors.toList())));
  }

  /** Every copy of {@code artifactId} the distribution ships, in no particular order. */
  public List<BundledJar> jarsOf(String artifactId) {
    return jarsByArtifactId.getOrDefault(artifactId, List.of());
  }

  /** One copy of {@code artifactId} at {@code version}; several modules may ship the same one. */
  public Optional<BundledJar> jarOf(String artifactId, String version) {
    return jarsOf(artifactId).stream().filter(jar -> jar.version().equals(version)).findFirst();
  }

  /** Every directory shipping {@code artifactId}, which several modules may do. */
  public Set<Path> directoriesShipping(String artifactId) {
    return jarsOf(artifactId).stream().map(BundledJar::directory).collect(Collectors.toSet());
  }

  /** The jars one directory of the distribution ships, ordered by file name. */
  public List<BundledJar> jarsIn(Path directory) {
    return jarsByDirectory.getOrDefault(directory, List.of());
  }

  /**
   * The directory whose contents make up {@code artifactId}'s classpath: {@code lib} for the server, and a
   * module's own directory for a module.
   *
   * <p>An artifact is shipped by every module that needs it, so several directories can hold it. The one named
   * after it wins, that being the module the artifact belongs to; otherwise the fullest one, that being the
   * module ES loads the artifact's dependencies with -- {@code elasticsearch-rest-client} is shipped by three
   * modules, and only {@code reindex} ships the HTTP client jars it needs alongside it. Equal sizes are broken
   * by path, so repeated scans of one distribution answer the same.
   */
  public Optional<Path> classpathDirOf(String artifactId) {
    Comparator<Path> theArtifactsOwnDirectoryThenTheFullest =
        Comparator.<Path, Boolean>comparing(dir -> dir.getFileName().toString().equals(artifactId))
            .thenComparingInt(dir -> jarsIn(dir).size())
            .thenComparing(Path::toString);
    return directoriesShipping(artifactId).stream().max(theArtifactsOwnDirectoryThenTheFullest);
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

  private static Stream<BundledJar> jarsOfDirectory(Path dir) {
    File[] files = dir.toFile().listFiles();
    if (files == null) {
      return Stream.empty();
    }
    return Stream.of(files)
        .filter(file -> file.getName().endsWith(".jar"))
        // The file system orders a directory as it pleases, and what is read here ends up in the
        // POMs.
        .sorted(Comparator.comparing(File::getName))
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
