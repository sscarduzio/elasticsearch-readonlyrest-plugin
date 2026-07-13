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
package tech.beshu.ror.buildbase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Partitions integration-test suites across K parallel shard JVMs (the other half of this
 * feature is {@link ShardedGradlewTest}, which spawns those JVMs). Pure function of its
 * inputs — no Gradle types, no I/O — so the partitioning invariants are unit-testable:
 * every suite lands in exactly one shard, and the same input always yields the same partition.
 *
 * <p>Two packing modes:
 *
 * <ul>
 *   <li><b>Hash</b> (empty {@code timings}): {@code floorMod(hashCode, K)}. Duration-blind and
 *       uneven, but the idle gaps double as memory slack — safe on small runners even without
 *       a concurrency gate.
 *   <li><b>Balanced</b> (non-empty {@code timings}): LPT bin-packing by measured suite wall time
 *       (suites sorted by weight descending, each assigned to the currently lightest shard).
 *       Levels the shards, BUT the sustained full load needs a cap on concurrent multi-node
 *       suites ({@code ROR_HEAVY_SUITE_PERMITS}) on 16 GB machines — ungated balanced packing
 *       host-OOMed them consistently. Suites missing from {@code timings} default to
 *       {@value #DEFAULT_WEIGHT_SECONDS}s and still run; stale timing entries for deleted
 *       suites are ignored. Ties (equal weight / equally light bins) break deterministically
 *       by name and lowest shard index.
 * </ul>
 */
public final class SuiteSharder {

  static final long DEFAULT_WEIGHT_SECONDS = 60;

  private SuiteSharder() {}

  /**
   * Returns the subset of {@code allSuites} that shard {@code shardIndex} of {@code shardCount}
   * must run.
   *
   * @param allSuites fully-qualified class names of every discovered suite
   * @param shardCount total number of shards (K)
   * @param shardIndex this shard's index, {@code 0 <= shardIndex < shardCount}
   * @param timings measured suite wall times in seconds; empty map selects hash packing
   */
  public static List<String> suitesFor(
      List<String> allSuites, int shardCount, int shardIndex, Map<String, Long> timings) {
    if (shardCount < 1 || shardIndex < 0 || shardIndex >= shardCount) {
      throw new IllegalArgumentException(
          "Invalid shard spec: index " + shardIndex + " of " + shardCount);
    }
    return timings.isEmpty()
        ? hashPacked(allSuites, shardCount, shardIndex)
        : balancedPacked(allSuites, shardCount, shardIndex, timings);
  }

  private static List<String> hashPacked(List<String> allSuites, int shardCount, int shardIndex) {
    List<String> result = new ArrayList<>();
    for (String suite : allSuites) {
      if (Math.floorMod(suite.hashCode(), shardCount) == shardIndex) {
        result.add(suite);
      }
    }
    return result;
  }

  private static List<String> balancedPacked(
      List<String> allSuites, int shardCount, int shardIndex, Map<String, Long> timings) {
    List<String> byWeightDesc = new ArrayList<>(allSuites);
    byWeightDesc.sort(
        Comparator.comparingLong((String s) -> weightOf(s, timings))
            .reversed()
            .thenComparing(Comparator.naturalOrder()));

    long[] bins = new long[shardCount];
    List<String> result = new ArrayList<>();
    for (String suite : byWeightDesc) {
      int lightest = 0;
      for (int i = 1; i < shardCount; i++) {
        if (bins[i] < bins[lightest]) {
          lightest = i;
        }
      }
      bins[lightest] += weightOf(suite, timings);
      if (lightest == shardIndex) {
        result.add(suite);
      }
    }
    return result;
  }

  private static long weightOf(String suite, Map<String, Long> timings) {
    return timings.getOrDefault(suite, DEFAULT_WEIGHT_SECONDS);
  }
}
