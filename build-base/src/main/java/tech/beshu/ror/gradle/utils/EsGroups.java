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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The group Elastic publishes one of their own artifacts under, taken from what a Maven repository already
 * serves rather than from a list kept here.
 *
 * <p>Elastic use a handful of groups and move artifacts between them:
 * {@code elasticsearch-plugin-api} was published under {@code org.elasticsearch} up to 8.6.2 and under
 * {@code org.elasticsearch.plugin} from 8.7 on. So the question is not which group an artifact belongs to but
 * which one it belonged to at a version -- answered by asking each group for the versions it publishes and
 * keeping the group that got closest to the one being mirrored without passing it.
 *
 * <p>The release being mirrored is not on Central yet, which is the whole reason for mirroring it; the
 * releases before it are, and a group is not renamed by the release that follows the one that moved it.
 */
public final class EsGroups implements ArtifactGroups {

  /** The group Elastic publishes under unless a project of theirs says otherwise. */
  public static final String DEFAULT_GROUP = "org.elasticsearch";

  /** The groups Elastic publishes under. An artifact of theirs is under one of these or under none. */
  private static final List<String> ES_GROUPS =
      List.of(DEFAULT_GROUP, "org.elasticsearch.plugin", "org.elasticsearch.client");

  private final MavenRepository repository;
  private final Map<String, Optional<String>> answered = new HashMap<>();

  private EsGroups(MavenRepository repository) {
    this.repository = repository;
  }

  /** Reads the groups from {@code repository}, which has to be one Elastic publishes to. */
  public static EsGroups publishedIn(MavenRepository repository) {
    return new EsGroups(repository);
  }

  /**
   * Places what Elastic ships in a release without publishing anywhere -- {@code elasticsearch-log4j} is
   * log4j-core repackaged, and is on no repository. Carrying the release's own version is what marks a jar as
   * theirs; the group is then the one they publish everything else under.
   */
  public static ArtifactGroups shippedWith(String esVersion) {
    return (artifactId, version) ->
        version.equals(esVersion) ? Optional.of(DEFAULT_GROUP) : Optional.empty();
  }

  /**
   * Empty for an artifact none of Elastic's groups publishes, which is either not theirs or one they ship
   * without publishing -- {@code elasticsearch-log4j} is on no repository at all.
   */
  @Override
  public Optional<String> groupOf(String artifactId, String version) {
    return answered.computeIfAbsent(artifactId + ":" + version, key -> lookUp(artifactId, version));
  }

  private Optional<String> lookUp(String artifactId, String version) {
    String closest = null;
    String closestGroup = null;
    for (String group : ES_GROUPS) {
      Optional<String> published = newestUpTo(version, new MavenCoordinate(group, artifactId));
      if (published.isPresent()
          && (closest == null
              || EsVersions.VERSION_COMPARATOR.compare(published.get(), closest) > 0)) {
        closest = published.get();
        closestGroup = group;
      }
    }
    return Optional.ofNullable(closestGroup);
  }

  private Optional<String> newestUpTo(String version, MavenCoordinate coordinate) {
    return repository.publishedVersions(coordinate).stream()
        .filter(published -> EsVersions.VERSION_COMPARATOR.compare(published, version) <= 0)
        .max(EsVersions.VERSION_COMPARATOR);
  }
}
