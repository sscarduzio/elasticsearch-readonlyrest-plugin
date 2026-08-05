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
import tech.beshu.ror.gradle.utils.EsDistribution.BundledJar;
import tech.beshu.ror.gradle.utils.MavenPoms.Coordinate;
import tech.beshu.ror.gradle.utils.MavenPoms.Dependency;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans the jars and POMs to publish to the ROR libs store for an ES version Elastic has released but not yet
 * published to Maven Central.
 *
 * <p>The store serves plain files, so a jar published on its own resolves as a leaf and brings none of the ES
 * dependency graph with it. POMs are published alongside the jars to supply that graph. Each POM is derived
 * from the one Elastic published for the closest ES version at or below the one being published, with every
 * dependency version replaced by the version that distribution bundles; the jars to publish are then the ES
 * artifacts those dependencies name.
 */
public final class EsLibsMirror {

  public record MirroredPom(Coordinate coordinate, String version, List<Dependency> dependencies) {

    public String fileName() {
      return coordinate.pomFileName(version);
    }
  }

  public record MirroredJar(Coordinate coordinate, String version, Path file) {

    /** The name the distribution gives the jar, which is the {@code artifactId-version.jar} Maven expects. */
    public String fileName() {
      return file.getFileName().toString();
    }
  }

  /** @param referenceVersion the published ES version the POMs are shaped after */
  public record MirrorPlan(
      String referenceVersion, List<MirroredPom> poms, List<MirroredJar> jars) {}

  private static final String ES_GROUP_PREFIX = "org.elasticsearch";

  /** The artifact whose published versions tell whether Central has an ES release yet. */
  private static final Coordinate ELASTICSEARCH =
      new Coordinate("org.elasticsearch", "elasticsearch");

  /** The ES artifacts the es*x modules depend on, and so the ones the store has to serve POMs for. */
  public static final List<Coordinate> MIRRORED_COORDINATES =
      List.of(
          ELASTICSEARCH,
          new Coordinate("org.elasticsearch.plugin", "transport-netty4"),
          new Coordinate("org.elasticsearch.client", "elasticsearch-rest-client"));

  private EsLibsMirror() {}

  /** Plans what to publish for {@code targetVersion}, taking the reference POMs from {@code reference}. */
  public static MirrorPlan planFor(
      EsDistribution distribution, String targetVersion, MavenRepository reference) {
    return planFor(distribution, targetVersion, MIRRORED_COORDINATES, reference);
  }

  static MirrorPlan planFor(
      EsDistribution distribution,
      String targetVersion,
      List<Coordinate> coordinates,
      MavenRepository reference) {
    String referenceVersion =
        referenceVersion(reference.publishedVersions(ELASTICSEARCH), targetVersion);
    Map<Coordinate, List<Dependency>> referenceDependencies = new LinkedHashMap<>();
    for (Coordinate coordinate : coordinates) {
      referenceDependencies.put(
          coordinate, MavenPoms.parseDependencies(reference.pomOf(coordinate, referenceVersion)));
    }
    return plan(distribution, targetVersion, referenceVersion, referenceDependencies);
  }

  /**
   * The published version whose POMs the generated ones are derived from: {@code targetVersion} itself when
   * Elastic has published it, otherwise the newest published version below it, preferring the same major.
   */
  static String referenceVersion(List<String> publishedVersions, String targetVersion) {
    if (publishedVersions.contains(targetVersion)) {
      return targetVersion;
    }
    List<String> earlier =
        publishedVersions.stream()
            .filter(version -> EsVersions.VERSION_COMPARATOR.compare(version, targetVersion) < 0)
            .toList();
    if (earlier.isEmpty()) {
      throw new GradleException(
          "Cannot generate POMs for ES "
              + targetVersion
              + ": Maven Central has no earlier version to take the dependencies from");
    }
    String targetMajor = majorOf(targetVersion);
    List<String> sameMajor =
        earlier.stream().filter(version -> majorOf(version).equals(targetMajor)).toList();
    return (sameMajor.isEmpty() ? earlier : sameMajor)
        .stream().max(EsVersions.VERSION_COMPARATOR).orElseThrow();
  }

  /**
   * @param referenceDependencies the dependencies each coordinate declares in its {@code referenceVersion}
   *     POM, in declaration order, which the generated POMs keep
   */
  static MirrorPlan plan(
      EsDistribution distribution,
      String targetVersion,
      String referenceVersion,
      Map<Coordinate, List<Dependency>> referenceDependencies) {
    List<MirroredPom> poms = new ArrayList<>();
    for (Map.Entry<Coordinate, List<Dependency>> entry : referenceDependencies.entrySet()) {
      Coordinate coordinate = entry.getKey();
      Set<Path> dirsShippingTheArtifact = distribution.directoriesShipping(coordinate.artifactId());
      if (dirsShippingTheArtifact.isEmpty()) {
        throw new GradleException(
            coordinate.artifactId()
                + "-"
                + targetVersion
                + ".jar is not bundled in the ES "
                + targetVersion
                + " distribution");
      }
      List<Dependency> dependencies =
          entry.getValue().stream()
              .map(
                  dependency ->
                      versionedForTarget(
                          dependency,
                          distribution,
                          dirsShippingTheArtifact,
                          coordinate,
                          targetVersion,
                          referenceVersion))
              .toList();
      poms.add(new MirroredPom(coordinate, targetVersion, dependencies));
    }
    return new MirrorPlan(
        referenceVersion, List.copyOf(poms), jarsFor(poms, distribution, targetVersion));
  }

  /**
   * Retargets one dependency at {@code targetVersion}: it takes the version the distribution bundles, or keeps
   * the reference POM's version when the distribution does not ship the artifact at all (log4j-core, for one).
   * A dependency versioned with ES that the distribution does not ship means the reference POM no longer
   * describes this release, and fails rather than naming a jar that cannot be published.
   */
  private static Dependency versionedForTarget(
      Dependency dependency,
      EsDistribution distribution,
      Set<Path> dirsShippingTheArtifact,
      Coordinate coordinate,
      String targetVersion,
      String referenceVersion) {
    return distribution
        .preferredJarOf(dependency.artifactId(), dirsShippingTheArtifact)
        .map(bundled -> dependency.withVersion(bundled.version()))
        .orElseGet(
            () -> {
              if (dependency.version().equals(referenceVersion)) {
                throw new GradleException(
                    "ES "
                        + targetVersion
                        + " does not bundle '"
                        + dependency.artifactId()
                        + "', which "
                        + coordinate.artifactId()
                        + " "
                        + referenceVersion
                        + " depends on");
              }
              return dependency;
            });
  }

  /** The planned coordinates plus every ES artifact their POMs name; the rest resolve from Central. */
  private static List<MirroredJar> jarsFor(
      List<MirroredPom> poms, EsDistribution distribution, String targetVersion) {
    Set<Coordinate> coordinates = new LinkedHashSet<>();
    poms.forEach(pom -> coordinates.add(pom.coordinate()));
    poms.stream()
        .flatMap(pom -> pom.dependencies().stream())
        .filter(dependency -> dependency.groupId().startsWith(ES_GROUP_PREFIX))
        .forEach(
            dependency ->
                coordinates.add(new Coordinate(dependency.groupId(), dependency.artifactId())));

    List<MirroredJar> jars = new ArrayList<>();
    for (Coordinate coordinate : coordinates) {
      BundledJar bundled =
          distribution
              .preferredJarOf(coordinate.artifactId(), Set.of())
              .orElseThrow(
                  () ->
                      new GradleException(
                          coordinate.artifactId()
                              + "-"
                              + targetVersion
                              + ".jar is not bundled in the ES "
                              + targetVersion
                              + " distribution"));
      jars.add(new MirroredJar(coordinate, bundled.version(), bundled.file()));
    }
    return List.copyOf(jars);
  }

  private static String majorOf(String version) {
    return version.split("\\.", 2)[0];
  }
}
