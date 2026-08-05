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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out what to mirror to the ROR libs store for an ES version Elastic has released but not yet published
 * to Maven Central.
 *
 * <p>The store serves plain files, so a jar uploaded on its own carries no dependency information; the es*x
 * modules would resolve it as a leaf and lose the whole ES graph. We therefore also publish POMs for the
 * coordinates those modules declare. Rather than describing that graph by hand, the POMs are shaped after the
 * ones Elastic HAS published for the closest earlier version, with every version swapped for the one the
 * distribution being mirrored actually bundles -- so a new ES release picks up Elastic's own dependency
 * changes, and the jars to mirror simply follow from the resulting dependencies.
 */
public final class EsLibsMirror {

  /** A POM to generate: the coordinate, and the dependencies it should declare. */
  public record MirroredPom(Coordinate coordinate, String version, List<Dependency> dependencies) {}

  /** A jar to copy out of the distribution and upload. */
  public record MirroredJar(Coordinate coordinate, String version, Path file) {}

  public record MirrorPlan(List<MirroredPom> poms, List<MirroredJar> jars) {}

  private static final String ES_GROUP_PREFIX = "org.elasticsearch";

  private EsLibsMirror() {}

  /**
   * The published version whose POMs to copy the shape from: {@code targetVersion} itself when Elastic has
   * already published it, otherwise the newest published version below it, preferring the same major so the
   * reference stays inside the same release line.
   */
  public static String referenceVersion(List<String> publishedVersions, String targetVersion) {
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
   * @param distribution the extracted distribution of {@code targetVersion}
   * @param referenceDependencies the dependencies each mirrored coordinate declares in {@code
   *     referenceVersion}'s published POM, in the order the POM declares them
   */
  public static MirrorPlan plan(
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
    return new MirrorPlan(List.copyOf(poms), jarsFor(poms, distribution, targetVersion));
  }

  /**
   * A dependency as the target version declares it: the version the distribution bundles when it ships the
   * artifact at all. Anything versioned with ES that the distribution does NOT ship means the reference POM no
   * longer describes this release, which has to fail rather than produce a POM pointing at a missing jar.
   * Anything else (log4j-core, which ES does not bundle) keeps the version Elastic itself declared.
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

  /** The mirrored coordinates plus every ES artifact their POMs point at; anything else comes from Central. */
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
