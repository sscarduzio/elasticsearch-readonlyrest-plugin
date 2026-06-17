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

  /**
   * Hard ceiling regardless of how big/idle the box looks. Past this, the one-time singleton image
   * pre-build + per-worker ES bootstrap stop paying off and the Docker daemon becomes the bottleneck.
   * Empirically (local sweep) the speedup curve already flattens well before this.
   */
  static final int MAX_FORKS = 8;

  private IntegrationTestForkCount() {
  }

  /**
   * Static sizing (legacy): bound by total CPU and total RAM. Kept for callers/tests that don't
   * supply live signals; prefer {@link #resolveDynamic} on shared hosts.
   */
  public static int resolve(String explicitOverride,
                            boolean isWindows,
                            int availableCpus,
                            double totalRamGb) {
    Integer pinned = preResolve(explicitOverride, isWindows);
    if (pinned != null) {
      return pinned;
    }
    int cpuBound = Math.max(1, availableCpus);
    int ramBound = forksFitting(totalRamGb - RESERVED_RAM_GB);
    return clamp(Math.min(cpuBound, ramBound));
  }

  /**
   * Sizing for a SHARED self-hosted host that runs several IT legs at once (N Azure agents on one
   * box). The earlier load-average heuristic was self-defeating: every agent reads the load that the
   * OTHER agents create, so all of them throttle to 1 fork and the box sits half-idle while legs run
   * serially (observed on ryzen: 5 agents, load ~15, every leg maxParallelForks=1, ~no speedup).
   * <p>
   * Instead we partition the box DETERMINISTICALLY by how many legs share it concurrently
   * ({@code agentsPerHost}, from {@code IT_AGENTS_PER_HOST}). Each leg gets its fair slice:
   * <ul>
   *   <li><b>CPU:</b> {@code physicalCores / agentsPerHost} (SMT threads excluded — ES bootstrap is
   *       CPU-spiky and hyperthreads don't help it).</li>
   *   <li><b>RAM:</b> {@code (totalRamGb - reserved) / agentsPerHost / perWorkerRam}.</li>
   * </ul>
   * Tighter of the two, clamped to [1, {@value #MAX_FORKS}]. No load feedback, so it can't collapse
   * to 1 under self-inflicted contention: with 2 agents on an 8-core/62 GB box each leg gets ~4 forks.
   * Tune the agent count down (fewer agents, more forks each) for the best total throughput.
   *
   * @param explicitOverride {@code IT_MAX_PARALLEL_FORKS} (wins verbatim if set)
   * @param isWindows        pinned to 1 (fixed ES container ports)
   * @param physicalCores    physical cores (NOT SMT threads); &lt;=0 if undetectable
   * @param totalRamGb       total physical RAM in GB; &lt;=0 if undetectable
   * @param agentsPerHost    concurrent IT legs sharing this host ({@code IT_AGENTS_PER_HOST}); &lt;1 -&gt; 1
   */
  public static int resolvePerAgent(String explicitOverride,
                                    boolean isWindows,
                                    int physicalCores,
                                    double totalRamGb,
                                    int agentsPerHost) {
    Integer pinned = preResolve(explicitOverride, isWindows);
    if (pinned != null) {
      return pinned;
    }
    int agents = Math.max(1, agentsPerHost);
    int cpuBound = physicalCores <= 0 ? 1 : Math.max(1, physicalCores / agents);
    int ramBound = forksFitting((totalRamGb - RESERVED_RAM_GB) / agents);
    return clamp(Math.min(cpuBound, ramBound));
  }

  /** Override / Windows short-circuit shared by both resolvers; null = "keep computing". */
  private static Integer preResolve(String explicitOverride, boolean isWindows) {
    Integer override = parseOverride(explicitOverride);
    if (override != null) {
      return Math.max(1, override);
    }
    if (isWindows) {
      return 1;
    }
    return null;
  }

  /** How many per-worker RAM slices fit in {@code freeGb}; <=0 / too-small means just 1. */
  private static int forksFitting(double freeGb) {
    if (freeGb < PER_WORKER_RAM_GB) {
      return 1;
    }
    return (int) Math.floor(freeGb / PER_WORKER_RAM_GB);
  }

  private static int clamp(int n) {
    return Math.max(1, Math.min(MAX_FORKS, n));
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
