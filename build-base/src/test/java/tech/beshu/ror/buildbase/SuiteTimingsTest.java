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

import java.util.List;
import java.util.Map;

class SuiteTimingsTest {

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
