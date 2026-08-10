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
import tech.beshu.ror.gradle.utils.MavenPoms.Dependency;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Plans the jars and POMs to publish to the ROR libs store for an ES version Elastic has released but not yet
 * published to Maven Central.
 *
 * <p>The store serves plain files, so a jar published on its own resolves as a leaf and brings none of the ES
 * dependency graph with it. POMs are published alongside the jars to supply that graph.
 *
 * <p>What a POM declares is read out of the distribution being mirrored: an artifact's dependencies are the
 * jars ES ships on its classpath, which is {@code lib} for the server and its own directory for a module.
 * Nothing is taken from an earlier release, so a jar this one adds is published and a jar it drops is not,
 * neither of which anyone has to notice. The distribution names an artifact and its version but never the
 * group it is published under, which {@link ArtifactGroups} answers.
 *
 * <p>That makes the POMs wider than the ones Elastic publishes, which declare what the artifact needs rather
 * than everything ES loads beside it. Wider is the safe direction: the extra entries are jars ES itself ships,
 * and the alternative is a dependency nothing resolves.
 */
public final class EsLibsMirror {

  public record MirroredPom(
      MavenCoordinate coordinate, String version, List<Dependency> dependencies) {

    public String fileName() {
      return coordinate.pomFileName(version);
    }
  }

  public record MirroredJar(MavenCoordinate coordinate, String version, Path file) {

    /** The name the distribution gives the jar, which is the {@code artifactId-version.jar} Maven expects. */
    public String fileName() {
      return file.getFileName().toString();
    }
  }

  public record MirrorPlan(List<MirroredPom> poms, List<MirroredJar> jars) {

    /**
     * The POM to publish alongside {@code jar}, when the plan has one -- jars mirrored only because a POM names
     * them as a dependency have none.
     *
     * <p>A jar and its POM share a repository directory named for the jar's version, so a POM naming a
     * different version would be published under a name Maven never asks for there, and the version it does
     * name would be left with no POM at all. Fails rather than uploading that.
     */
    public Optional<MirroredPom> pomFor(MirroredJar jar) {
      Optional<MirroredPom> pom =
          poms.stream()
              .filter(candidate -> candidate.coordinate().equals(jar.coordinate()))
              .findFirst();
      pom.filter(found -> !found.version().equals(jar.version()))
          .ifPresent(
              found -> {
                throw new GradleException(
                    "Cannot publish "
                        + found.fileName()
                        + " alongside "
                        + jar.fileName()
                        + ": the POM and the bundled jar name different versions");
              });
      return pom;
    }
  }

  /**
   * Everything a generated POM declares is on the classpath ES loads the artifact with, so it is there to be
   * compiled and run against.
   */
  private static final String SCOPE = "compile";

  /** The ES artifacts the es*x modules depend on, and so the ones the store has to serve POMs for. */
  public static final List<String> MIRRORED_ARTIFACTS =
      List.of("elasticsearch", "transport-netty4", "elasticsearch-rest-client");

  private EsLibsMirror() {}

  /** Plans what to publish out of an extracted distribution, placing artifacts with {@code groups}. */
  public static MirrorPlan planFor(EsDistribution distribution, ArtifactGroups groups) {
    return planFor(distribution, MIRRORED_ARTIFACTS, groups);
  }

  static MirrorPlan planFor(
      EsDistribution distribution, List<String> artifacts, ArtifactGroups groups) {
    List<MirroredPom> poms =
        artifacts.stream().map(artifact -> pomOf(artifact, distribution, groups)).toList();
    return new MirrorPlan(poms, jarsToPublish(poms, distribution));
  }

  /** What one artifact needs: the jars ES loads it with, which is what its classpath directory holds. */
  private static MirroredPom pomOf(
      String artifact, EsDistribution distribution, ArtifactGroups groups) {
    Path classpathDir = classpathDirOf(artifact, distribution);
    BundledJar bundled = jarIn(classpathDir, artifact, distribution);
    List<Dependency> dependencies =
        distribution.jarsIn(classpathDir).stream()
            .filter(jar -> isADependencyOf(jar, artifact, classpathDir))
            .map(jar -> dependencyOn(jar, groups))
            .toList();
    // The POM carries the bundled jar's version, so that a POM and the jar it is published beside
    // always
    // name the same version -- see MirrorPlan.pomFor.
    return new MirroredPom(
        coordinateOf(bundled, groups), bundled.version(), List.copyOf(dependencies));
  }

  /**
   * The jars to upload: the artifacts themselves and the ones only Elastic publishes, which this release is
   * too new for. Everything else a POM declares is published under its own version and already resolves.
   */
  private static List<MirroredJar> jarsToPublish(
      List<MirroredPom> poms, EsDistribution distribution) {
    Stream<MavenCoordinateAt> theArtifacts =
        poms.stream().map(pom -> new MavenCoordinateAt(pom.coordinate(), pom.version()));
    Stream<MavenCoordinateAt> theirElasticDependencies =
        poms.stream()
            .flatMap(pom -> pom.dependencies().stream())
            .filter(EsLibsMirror::isElastics)
            .map(
                dependency ->
                    new MavenCoordinateAt(
                        new MavenCoordinate(dependency.groupId(), dependency.artifactId()),
                        dependency.version()));
    return Stream.concat(theArtifacts, theirElasticDependencies)
        .distinct()
        .map(at -> at.mirroredFrom(distribution))
        .toList();
  }

  /** One version of one coordinate, which is what the store holds a directory of. */
  private record MavenCoordinateAt(MavenCoordinate coordinate, String version) {

    MirroredJar mirroredFrom(EsDistribution distribution) {
      return distribution
          .jarOf(coordinate.artifactId(), version)
          .map(bundled -> new MirroredJar(coordinate, version, bundled.file()))
          .orElseThrow();
    }
  }

  private static boolean isElastics(Dependency dependency) {
    return dependency.groupId().startsWith(EsGroups.DEFAULT_GROUP);
  }

  /**
   * Everything in the classpath directory except the artifact itself and, when the directory is a module's,
   * the module's own jar -- a module ships what it needs alongside what it is.
   */
  private static boolean isADependencyOf(BundledJar jar, String artifact, Path classpathDir) {
    return !jar.artifactId().equals(artifact)
        && !jar.artifactId().equals(classpathDir.getFileName().toString());
  }

  private static Dependency dependencyOn(BundledJar jar, ArtifactGroups groups) {
    MavenCoordinate coordinate = coordinateOf(jar, groups);
    return new Dependency(coordinate.groupId(), coordinate.artifactId(), jar.version(), SCOPE);
  }

  /** A jar names its artifact and version; only {@code groups} knows where it is published. */
  private static MavenCoordinate coordinateOf(BundledJar jar, ArtifactGroups groups) {
    String group =
        groups
            .groupOf(jar.artifactId(), jar.version())
            .orElseThrow(
                () ->
                    new GradleException(
                        "Cannot tell which Maven group "
                            + jar.file().getFileName()
                            + " is published under: nothing that places an artifact names it."));
    return new MavenCoordinate(group, jar.artifactId());
  }

  private static Path classpathDirOf(String artifact, EsDistribution distribution) {
    return distribution
        .classpathDirOf(artifact)
        .orElseThrow(
            () ->
                new GradleException(
                    artifact + " is not bundled in the ES distribution being mirrored"));
  }

  private static BundledJar jarIn(Path directory, String artifact, EsDistribution distribution) {
    return distribution.jarsIn(directory).stream()
        .filter(jar -> jar.artifactId().equals(artifact))
        .findFirst()
        .orElseThrow();
  }
}
