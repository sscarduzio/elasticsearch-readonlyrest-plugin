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

import org.gradle.api.Project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Which ES modules a test run covers. See "Test matrix policy" in ci/CI.md, which this class
 * implements. The policy applies to each ES major on its own, so a new major joins every matrix by
 * itself.
 *
 * <p>The matrices were hand-written lists before. A list drifts from the modules that exist, and
 * from the policy it is supposed to follow.
 */
public final class TestMatrixPolicy {

  /**
   * A ready PR runs a middle module too, once a major holds this many. Below it, oldest and newest
   * already sit close together.
   */
  private static final int MIDDLE_MODULE_THRESHOLD = 10;

  private TestMatrixPolicy() {}

  /** How much of one ES major a test family covers. */
  public enum Selection {
    /** Every module. */
    ALL,
    /** The newest module only. */
    NEWEST,
    /** The newest and the oldest module. */
    OLDEST_AND_NEWEST,
    /** Newest and oldest, plus a middle module once the major holds enough of them. */
    READY_PR
  }

  /**
   * The modules a selection covers, newest major first, and newest module first inside a major.
   *
   * @param skippedMajors majors this test family does not run at all
   */
  public static List<String> modulesFor(
      Project rootProject, Selection selection, Set<Integer> skippedMajors) {
    List<String> selected = new ArrayList<>();
    for (Integer esMajor : EsModuleFinder.allSupportedEsMajors(rootProject)) {
      if (skippedMajors.contains(esMajor)) {
        continue;
      }
      selected.addAll(
          select(EsModuleFinder.esModuleNamesForMajor(rootProject, esMajor), selection));
    }
    return selected;
  }

  /**
   * The modules a selection covers inside one ES major. A major with one module gives that module
   * once, whatever the selection asks for.
   *
   * @param modulesNewestFirst the modules of one ES major, newest first
   */
  public static List<String> select(List<String> modulesNewestFirst, Selection selection) {
    int moduleCount = modulesNewestFirst.size();
    if (moduleCount == 0) {
      throw new IllegalArgumentException("An ES major with no module cannot be selected from");
    }
    if (selection == Selection.ALL) {
      return new ArrayList<>(modulesNewestFirst);
    }
    if (selection == Selection.NEWEST || moduleCount == 1) {
      return Collections.singletonList(modulesNewestFirst.get(0));
    }
    String newest = modulesNewestFirst.get(0);
    String oldest = modulesNewestFirst.get(moduleCount - 1);
    if (selection == Selection.OLDEST_AND_NEWEST || moduleCount < MIDDLE_MODULE_THRESHOLD) {
      return Arrays.asList(newest, oldest);
    }
    return Arrays.asList(newest, modulesNewestFirst.get(moduleCount / 2), oldest);
  }
}
