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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Drift detection between committed suite timings (integration-tests/suite-timings.json) and
 * freshly measured ones. Pure function of its inputs — the Gradle `regenerateSuiteTimings` task
 * does the XML/JSON I/O and hands the maps here — so the warning thresholds are unit-tested
 * (same split as {@link SuiteSharder}).
 */
public final class SuiteTimings {

  /**
   * A timing change is reported only when it is big in BOTH senses: more than
   * {@link #DRIFT_ABS_SECONDS} absolute (ignores jitter on short suites) and more than
   * {@link #DRIFT_REL} of the committed value (ignores small relative wobble on long suites).
   */
  static final long DRIFT_ABS_SECONDS = 60;

  static final double DRIFT_REL = 0.5;

  private SuiteTimings() {}

  /**
   * Returns one human-readable line per drifted or new suite, sorted by suite name.
   * Suites present only in {@code committed} (deleted suites) are not reported — stale
   * entries are harmless to the sharder and get dropped on the next re-baseline.
   */
  public static List<String> driftReport(Map<String, Long> committed, Map<String, Long> measured) {
    List<String> drifts = new ArrayList<>();
    new TreeMap<>(measured)
        .forEach(
            (suite, seconds) -> {
              Long old = committed.get(suite);
              if (old == null) {
                drifts.add("NEW " + suite + ": " + seconds + "s (missing from suite-timings.json)");
              } else if (Math.abs(seconds - old) > DRIFT_ABS_SECONDS
                  && Math.abs(seconds - old) > DRIFT_REL * old) {
                drifts.add("DRIFT " + suite + ": " + old + "s -> " + seconds + "s");
              }
            });
    return drifts;
  }
}
