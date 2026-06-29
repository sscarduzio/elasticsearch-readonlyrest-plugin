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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.Project;

/**
 * Parses a module's {@code supportedEsVersions} gradle property (oldest-first CSV -- the single source of
 * truth for which ES versions a module publishes) into the version list and its oldest/newest ends. Shared
 * by the readonlyrest.* convention plugins so this parsing lives in exactly one place.
 */
public final class EsVersions {

  public final List<String> all;
  public final String oldest;
  public final String newest;

  private EsVersions(List<String> all) {
    this.all = all;
    this.oldest = all.get(0);
    this.newest = all.get(all.size() - 1);
  }

  public static EsVersions of(Project project) {
    List<String> list =
        Arrays.stream(((String) project.property("supportedEsVersions")).split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    if (list.isEmpty()) {
      throw new GradleException("supportedEsVersions is empty for project " + project.getName());
    }
    return new EsVersions(list);
  }

  /** The ES version to DELIVER: the {@code -PesVersion} override if given, otherwise the newest supported. */
  public static String delivered(Project project) {
    return project.hasProperty("esVersion")
        ? String.valueOf(project.property("esVersion"))
        : of(project).newest;
  }

  /**
   * The ES version to COMPILE against (the base): the {@code -PbaselineEsVersion} override if given (used only
   * by the CI bytecode guard), otherwise the oldest supported.
   */
  public static String baseline(Project project) {
    return project.hasProperty("baselineEsVersion")
        ? String.valueOf(project.property("baselineEsVersion"))
        : of(project).oldest;
  }
}
