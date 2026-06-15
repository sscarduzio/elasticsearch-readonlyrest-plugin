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

/**
 * Resource-aware fork count for the integration-test workers.
 * <p>
 * Each worker JVM ({@code maxHeapSize=2g}) leases its own singleton ES container
 * ({@code -Xmx512m}, ~1.2g RSS with the JVM + OS page cache overhead). So one worker costs
 * ~{@value #PER_WORKER_RAM_GB}g of RAM. Naively setting {@code maxParallelForks} to the CPU
 * count oversubscribes RAM on the small hosted CI agents (ubuntu-24.04 = 2 vCPU / 7 GB) and
 * pushes them into swap, which is exactly how parallel ES bootstrap turns flaky.
 * <p>
 * The fork count is therefore the tighter of the CPU bound and the RAM bound, never below 1.
 * An explicit {@code IT_MAX_PARALLEL_FORKS} override wins verbatim (operator knows their box);
 * Windows is always 1 because its ES containers use fixed host ports that collide across workers.
 * <p>
 * Pure and side-effect-free so the whole truth table is unit-tested without a real machine
 * (see {@code IntegrationTestForkCountTest}); the Gradle build injects the live values.
 */
public final class IntegrationTestForkCount {

  /** RAM a single worker (2g JVM heap + its ~1.2g ES container) realistically occupies. */
  static final double PER_WORKER_RAM_GB = 3.2d;

  /** Held back for the OS, the Docker daemon, Ryuk and build-tool overhead before budgeting workers. */
  static final double RESERVED_RAM_GB = 2.0d;

  private IntegrationTestForkCount() {
  }

  /**
   * @param explicitOverride value of {@code IT_MAX_PARALLEL_FORKS} ({@code null}/blank if unset)
   * @param isWindows        Windows hosts are pinned to 1 (fixed ES container ports)
   * @param availableCpus    {@code Runtime.availableProcessors()} on the build JVM
   * @param totalRamGb       total physical RAM in GB (0 or negative if undetectable)
   * @return the number of parallel test worker JVMs to run, always {@code >= 1}
   */
  public static int resolve(String explicitOverride,
                            boolean isWindows,
                            int availableCpus,
                            double totalRamGb) {
    Integer override = parseOverride(explicitOverride);
    if (override != null) {
      return Math.max(1, override);
    }
    if (isWindows) {
      return 1;
    }
    int cpuBound = Math.max(1, availableCpus);
    int ramBound = ramBound(totalRamGb);
    return Math.max(1, Math.min(cpuBound, ramBound));
  }

  private static int ramBound(double totalRamGb) {
    if (totalRamGb <= 0) {
      // RAM undetectable: stay conservative, let the CPU bound (or 1) decide.
      return 1;
    }
    double usable = totalRamGb - RESERVED_RAM_GB;
    if (usable < PER_WORKER_RAM_GB) {
      return 1;
    }
    return (int) Math.floor(usable / PER_WORKER_RAM_GB);
  }

  private static Integer parseOverride(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    try {
      return Integer.valueOf(trimmed);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "IT_MAX_PARALLEL_FORKS must be an integer, was: '" + raw + "'", e);
    }
  }
}
