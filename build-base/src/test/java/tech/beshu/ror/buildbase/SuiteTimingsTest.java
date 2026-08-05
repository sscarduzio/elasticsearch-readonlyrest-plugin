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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tech.beshu.ror.buildbase.SuiteTimings.SuiteRun;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class SuiteTimingsTest {

  private static SuiteRun run(String name, long startSeconds, double execSeconds) {
    return new SuiteRun(name, Instant.ofEpochSecond(startSeconds), execSeconds);
  }

  @Test
  void wallTimeIsBootPlusExec() {
    // A starts t=0 runs 10s; B starts t=25 => boot(B) = 25 - 10 = 15, wall(B) = 15 + 20 = 35.
    // A (first suite) gets the shard's median boot (only observation: 15) => wall(A) = 25.
    Map<String, Long> times =
        SuiteTimings.wallTimes(List.of(List.of(run("A", 0, 10.0), run("B", 25, 20.0))));
    assertEquals(Map.of("A", 25L, "B", 35L), times);
  }

  @Test
  void suitesAreOrderedByStartNotInputOrder() {
    Map<String, Long> times =
        SuiteTimings.wallTimes(List.of(List.of(run("B", 25, 20.0), run("A", 0, 10.0))));
    assertEquals(Map.of("A", 25L, "B", 35L), times);
  }

  @Test
  void singleSuiteShardFallsBackToDefaultBoot() {
    Map<String, Long> times = SuiteTimings.wallTimes(List.of(List.of(run("Only", 0, 12.0))));
    assertEquals(Map.of("Only", 42L), times); // 30s default boot + 12s exec
  }

  @Test
  void evenNumberOfBootGapsUsesTrueMedian() {
    // Gaps: boot(B) = 15-0-5 = 10, boot(C) = 40-15-5 = 20 => median = (10+20)/2 = 15,
    // so the first suite gets wall(A) = round(15 + 5) = 20 (not upper-middle 20+5).
    Map<String, Long> times =
        SuiteTimings.wallTimes(
            List.of(List.of(run("A", 0, 5.0), run("B", 15, 5.0), run("C", 40, 5.0))));
    assertEquals(20L, times.get("A"));
  }

  @Test
  void suiteSeenInSeveralShardsKeepsTheLargestEstimate() {
    Map<String, Long> times =
        SuiteTimings.wallTimes(List.of(List.of(run("A", 0, 5.0)), List.of(run("A", 0, 50.0))));
    assertEquals(Map.of("A", 80L), times);
  }

  @Test
  void negativeGapClampsBootToZero() {
    // B starts before A's exec finished (overlapping XMLs): boot must clamp to 0, not negative.
    Map<String, Long> times =
        SuiteTimings.wallTimes(List.of(List.of(run("A", 0, 30.0), run("B", 20, 10.0))));
    assertEquals(10L, times.get("B"));
  }

  @Test
  void subSecondSuiteNeverPacksWithZeroWeight() {
    // Fractional seconds survive the math (no truncation) and the result clamps to >= 1s.
    Map<String, Long> times =
        SuiteTimings.wallTimes(List.of(List.of(run("A", 0, 0.2), run("B", 0, 0.1))));
    assertTrue(times.values().stream().allMatch(t -> t >= 1L));
  }

  @Test
  void fractionalSecondsAreNotTruncated() {
    // gap = 10.9s, exec(A) = 0.4s => boot(B) = 10.5s; wall(B) = round(10.5 + 0.6) = 11, not 10.
    SuiteRun a = new SuiteRun("A", Instant.ofEpochMilli(0), 0.4);
    SuiteRun b = new SuiteRun("B", Instant.ofEpochMilli(10_900), 0.6);
    Map<String, Long> times = SuiteTimings.wallTimes(List.of(List.of(a, b)));
    assertEquals(11L, times.get("B"));
  }

  @Test
  void emptyInputYieldsEmptyMap() {
    assertEquals(Map.of(), SuiteTimings.wallTimes(List.of()));
  }

  @Test
  void reportsNothingWhenMeasurementsMatch() {
    Map<String, Long> committed = Map.of("SuiteA", 100L, "SuiteB", 20L);
    assertEquals(List.of(), SuiteTimings.driftReport(committed, committed));
  }

  @Test
  void reportsNewSuites() {
    List<String> drifts = SuiteTimings.driftReport(Map.of(), Map.of("SuiteA", 42L));
    assertEquals(1, drifts.size());
    assertTrue(drifts.get(0).startsWith("NEW SuiteA: 42s"));
  }

  @Test
  void ignoresDeletedSuites() {
    assertEquals(List.of(), SuiteTimings.driftReport(Map.of("Gone", 300L), Map.of()));
  }

  @Test
  void smallAbsoluteChangeOnShortSuiteIsNotDrift() {
    // 10s -> 65s is >DRIFT_REL relative but only 55s absolute (<= 60s): jitter, not drift.
    assertEquals(List.of(), SuiteTimings.driftReport(Map.of("Short", 10L), Map.of("Short", 65L)));
  }

  @Test
  void smallRelativeChangeOnLongSuiteIsNotDrift() {
    // 600s -> 700s is 100s absolute but under DRIFT_REL (50%) of committed: wobble, not drift.
    assertEquals(List.of(), SuiteTimings.driftReport(Map.of("Long", 600L), Map.of("Long", 700L)));
  }

  @Test
  void bigInBothSensesIsDrift() {
    List<String> drifts = SuiteTimings.driftReport(Map.of("SuiteA", 100L), Map.of("SuiteA", 300L));
    assertEquals(List.of("DRIFT SuiteA: 100s -> 300s"), drifts);
  }

  @Test
  void reportIsSortedBySuiteName() {
    List<String> drifts =
        SuiteTimings.driftReport(Map.of(), Map.of("Zeta", 1L, "Alpha", 1L, "Mid", 1L));
    assertEquals(3, drifts.size());
    assertTrue(drifts.get(0).contains("Alpha"));
    assertTrue(drifts.get(2).contains("Zeta"));
  }
}
