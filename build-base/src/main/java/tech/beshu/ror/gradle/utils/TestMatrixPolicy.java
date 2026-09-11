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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Which ES modules a test run covers. See "Test matrix policy" in ci/CI.md, which this class
 * implements. The policy applies to each ES major on its own, so a new major joins every matrix by
 * itself.
 *
 * <p>A hand-written list would drift from the modules that exist, and from the policy it is
 * supposed to follow.
 */
public final class TestMatrixPolicy {

  /**
   * A ready PR adds a middle module once a major holds this many. Below it, oldest and newest
   * already sit close together.
   */
  private static final int MIDDLE_MODULE_THRESHOLD = 10;

  /**
   * ES modules that no integration test covers, on Linux or on Windows. They are still built and
   * published, because {@code build_ror} and the release tasks group by major and enumerate modules
   * with {@code printEsModules}. So a regression specific to one of these modules ships untested.
   *
   * <p>This is a choice about cost, not a fact the build can derive, which is why it is written
   * down. Removing a name here gives that module its tests back, and costs runner minutes.
   */
  private static final Set<String> UNTESTED_MODULES =
      Set.of("es73x", "es74x", "es79x", "es711x", "es714x");

  private TestMatrixPolicy() {}

  /** How much of one ES major a test family covers. */
  public enum Selection {
    ALL,
    NEWEST,
    OLDEST_AND_NEWEST,
    /* Newest and oldest, plus a middle module once the major holds enough of them. */
    READY_PR
  }

  /**
   * The modules a selection covers, newest major first, and newest module first inside a major.
   *
   * @param skippedMajors majors this test family does not run at all
   */
  public static List<String> modulesFor(
      Project rootProject, Selection selection, Set<Integer> skippedMajors) {
    return EsModuleFinder.allSupportedEsMajors(rootProject).stream()
        .filter(esMajor -> !skippedMajors.contains(esMajor))
        .map(esMajor -> EsModuleFinder.esModuleNamesForMajor(rootProject, esMajor))
        .flatMap(modulesOfMajor -> select(modulesOfMajor, selection).stream())
        .collect(Collectors.toList());
  }

  /**
   * The modules a selection covers inside one ES major.
   *
   * @param modulesNewestFirst the modules of one ES major, newest first
   */
  public static List<String> select(List<String> modulesNewestFirst, Selection selection) {
    if (modulesNewestFirst.isEmpty()) {
      throw new IllegalArgumentException("An ES major with no module cannot be selected from");
    }
    return modulesAt(modulesNewestFirst, wantedPositions(modulesNewestFirst.size(), selection));
  }

  /**
   * Where in the major a selection wants its modules, counting from the newest. How many positions
   * there are follows from how many modules the major HOLDS, never from how many are tested.
   * {@link #modulesAt} then takes each module once, so a repeated position yields one module.
   */
  private static IntStream wantedPositions(int moduleCount, Selection selection) {
    int oldest = moduleCount - 1;
    return switch (selection) {
      case ALL -> IntStream.range(0, moduleCount);
      case NEWEST -> IntStream.of(0);
      case OLDEST_AND_NEWEST -> IntStream.of(0, oldest);
      case READY_PR ->
          moduleCount < MIDDLE_MODULE_THRESHOLD
              ? IntStream.of(0, oldest)
              : IntStream.of(0, moduleCount / 2, oldest);
    };
  }

  /**
   * One module per wanted position, in the order asked for. A position on an untested module, or
   * on one an earlier position took, moves to the nearest free module. A position with nothing
   * free left yields nothing.
   */
  private static List<String> modulesAt(
      List<String> modulesNewestFirst, IntStream wantedPositions) {
    List<String> taken = new ArrayList<>();
    wantedPositions.forEach(
        wanted -> nearestTested(modulesNewestFirst, wanted, taken).ifPresent(taken::add));
    return taken;
  }

  private static Optional<String> nearestTested(
      List<String> modulesNewestFirst, int wanted, List<String> alreadyTaken) {
    return positionsNearest(wanted, modulesNewestFirst.size())
        .mapToObj(modulesNewestFirst::get)
        .filter(module -> isTested(module) && !alreadyTaken.contains(module))
        .findFirst();
  }

  /** Every position, the nearest to {@code wanted} first, and the newer of two equally near ones. */
  private static IntStream positionsNearest(int wanted, int moduleCount) {
    return IntStream.range(0, moduleCount)
        .boxed()
        .sorted(
            Comparator.comparingInt((Integer position) -> Math.abs(position - wanted))
                .thenComparing(Comparator.naturalOrder()))
        .mapToInt(Integer::intValue);
  }

  private static boolean isTested(String module) {
    return !UNTESTED_MODULES.contains(module);
  }
}
