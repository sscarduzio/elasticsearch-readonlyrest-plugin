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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

class SuiteSharderTest {

  private static final List<String> SUITES =
      IntStream.range(0, 57).mapToObj(i -> "tech.beshu.ror.integration.suites.Suite" + i).toList();

  private static final Map<String, Long> TIMINGS = new HashMap<>();

  static {
    // uneven weights: a few monsters, a mid-range, and everything else missing (defaults)
    TIMINGS.put(SUITES.get(0), 430L);
    TIMINGS.put(SUITES.get(1), 338L);
    TIMINGS.put(SUITES.get(2), 250L);
    for (int i = 3; i < 30; i++) {
      TIMINGS.put(SUITES.get(i), 60L + i * 7L);
    }
    // suites 30..56 intentionally absent from timings
    TIMINGS.put("tech.beshu.ror.integration.suites.DeletedSuite", 999L); // stale entry
  }

  @Test
  void everySuiteLandsInExactlyOneShard_hashMode() {
    assertDisjointCover(Map.of(), 4);
  }

  @Test
  void everySuiteLandsInExactlyOneShard_balancedMode() {
    assertDisjointCover(TIMINGS, 4);
  }

  @Test
  void coverHoldsForEveryShardCount() {
    for (int k = 1; k <= 8; k++) {
      assertDisjointCover(Map.of(), k);
      assertDisjointCover(TIMINGS, k);
    }
  }

  @Test
  void samePartitionForSameInput() {
    for (int shard = 0; shard < 4; shard++) {
      assertEquals(
          SuiteSharder.suitesFor(SUITES, 4, shard, TIMINGS),
          SuiteSharder.suitesFor(SUITES, 4, shard, TIMINGS));
    }
  }

  @Test
  void inputOrderDoesNotChangeThePartition() {
    List<String> shuffled = new ArrayList<>(SUITES);
    java.util.Collections.shuffle(shuffled, new java.util.Random(42));
    for (int shard = 0; shard < 4; shard++) {
      List<String> fromOriginal = SuiteSharder.suitesFor(SUITES, 4, shard, TIMINGS);
      List<String> fromShuffled = SuiteSharder.suitesFor(shuffled, 4, shard, TIMINGS);
      assertEquals(sorted(fromOriginal), sorted(fromShuffled));
    }
  }

  @Test
  void missingTimingEntriesDegradeGracefully() {
    // only 2 of the suites have timings; the rest default — everything must still run somewhere
    Map<String, Long> sparse = Map.of(SUITES.get(0), 430L, SUITES.get(1), 338L);
    assertDisjointCover(sparse, 4);
  }

  @Test
  void staleTimingEntriesForDeletedSuitesAreIgnored() {
    List<String> assigned = allAssigned(TIMINGS, 4);
    assertTrue(assigned.stream().noneMatch(s -> s.contains("DeletedSuite")));
  }

  @Test
  void balancedModeLevelsTheBins() {
    long[] binWeights = new long[4];
    for (int shard = 0; shard < 4; shard++) {
      for (String suite : SuiteSharder.suitesFor(SUITES, 4, shard, TIMINGS)) {
        binWeights[shard] += TIMINGS.getOrDefault(suite, SuiteSharder.DEFAULT_WEIGHT_SECONDS);
      }
    }
    long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
    for (long w : binWeights) {
      min = Math.min(min, w);
      max = Math.max(max, w);
    }
    // LPT guarantee: no bin exceeds the average by more than the largest single item
    assertTrue(max - min <= 430L, "bin spread " + (max - min) + " exceeds largest suite weight");
  }

  @Test
  void singleShardRunsEverything() {
    assertEquals(sorted(SUITES), sorted(SuiteSharder.suitesFor(SUITES, 1, 0, TIMINGS)));
    assertEquals(sorted(SUITES), sorted(SuiteSharder.suitesFor(SUITES, 1, 0, Map.of())));
  }

  @Test
  void invalidShardSpecIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> SuiteSharder.suitesFor(SUITES, 0, 0, Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> SuiteSharder.suitesFor(SUITES, 4, 4, Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> SuiteSharder.suitesFor(SUITES, 4, -1, Map.of()));
  }

  private static void assertDisjointCover(Map<String, Long> timings, int shardCount) {
    List<String> assigned = allAssigned(timings, shardCount);
    assertEquals(SUITES.size(), assigned.size(), "duplicate or dropped suite assignment");
    assertEquals(sorted(SUITES), sorted(assigned));
  }

  private static List<String> allAssigned(Map<String, Long> timings, int shardCount) {
    List<String> assigned = new ArrayList<>();
    for (int shard = 0; shard < shardCount; shard++) {
      assigned.addAll(SuiteSharder.suitesFor(SUITES, shardCount, shard, timings));
    }
    return assigned;
  }

  private static List<String> sorted(List<String> xs) {
    return xs.stream().sorted().toList();
  }
}
