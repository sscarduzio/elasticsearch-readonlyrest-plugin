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

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * Wall-time measurement and drift detection for suite timings
 * (integration-tests/suite-timings.json). Pure functions of their inputs — the Gradle
 * `regenerateSuiteTimings` task does the XML/JSON I/O and hands the parsed runs/maps here — so
 * the estimation logic and warning thresholds are unit-tested (same split as {@link SuiteSharder}).
 */
public final class SuiteTimings {

  /**
   * A timing change is reported only when it is big in BOTH senses: more than
   * {@link #DRIFT_ABS_SECONDS} absolute (ignores jitter on short suites) and more than
   * {@link #DRIFT_REL} of the committed value (ignores small relative wobble on long suites).
   */
  static final long DRIFT_ABS_SECONDS = 60;

  static final double DRIFT_REL = 0.5;

  /** Boot estimate for a shard with a single suite, where no start-to-start gap is observable. */
  static final double DEFAULT_BOOT_SECONDS = 30.0;

  private SuiteTimings() {}

  /** One suite execution parsed from a junit XML: fully-qualified name, start, and exec time. */
  public record SuiteRun(String name, Instant start, double execSeconds) {}

  /**
   * Estimates WALL seconds per suite (container boot + test execution), not just the junit
   * `time` attribute: `time` is execution only, while shard-packing weights must include
   * container boots (often the dominant part). Suites run serially within a shard, so
   * start(i+1) - start(i) = exec(i) + boot(i+1), hence wall(i) = boot(i) + exec(i). The first
   * suite's boot is unobservable — the shard's median boot stands in. A suite seen in several
   * shards (or reruns) keeps its largest estimate; every value is clamped to at least 1s so no
   * suite packs with zero weight.
   */
  public static Map<String, Long> wallTimes(Collection<List<SuiteRun>> shards) {
    Map<String, Long> times = new TreeMap<>();
    shards.forEach(
        shard -> {
          List<SuiteRun> suites =
              shard.stream().sorted(Comparator.comparing(SuiteRun::start)).toList();
          List<Double> boots =
              IntStream.range(1, suites.size())
                  .mapToObj(
                      i -> {
                        double gap =
                            Duration.between(suites.get(i - 1).start(), suites.get(i).start())
                                    .toMillis()
                                / 1000.0;
                        return Math.max(0.0, gap - suites.get(i - 1).execSeconds());
                      })
                  .toList();
          double medianBoot = boots.isEmpty() ? DEFAULT_BOOT_SECONDS : median(boots);
          IntStream.range(0, suites.size())
              .forEach(
                  i -> {
                    SuiteRun s = suites.get(i);
                    double boot = i > 0 ? boots.get(i - 1) : medianBoot;
                    long wall = Math.max(1L, Math.round(boot + s.execSeconds()));
                    times.merge(s.name(), wall, Math::max);
                  });
        });
    return times;
  }

  /** True median: the middle value, or the mean of the two middle values for even-sized input. */
  private static double median(List<Double> values) {
    List<Double> sorted = values.stream().sorted().toList();
    int n = sorted.size();
    return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
  }

  /**
   * Returns one human-readable line per drifted or new suite, sorted by suite name.
   * Suites present only in {@code committed} (deleted suites) are not reported — stale
   * entries are harmless to the sharder and get dropped on the next re-baseline.
   */
  public static List<String> driftReport(Map<String, Long> committed, Map<String, Long> measured) {
    return measured.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .flatMap(e -> driftLine(committed.get(e.getKey()), e.getKey(), e.getValue()).stream())
        .toList();
  }

  private static Optional<String> driftLine(Long old, String suite, long seconds) {
    if (old == null) {
      return Optional.of("NEW " + suite + ": " + seconds + "s (missing from suite-timings.json)");
    }
    if (Math.abs(seconds - old) > DRIFT_ABS_SECONDS && Math.abs(seconds - old) > DRIFT_REL * old) {
      return Optional.of("DRIFT " + suite + ": " + old + "s -> " + seconds + "s");
    }
    return Optional.empty();
  }
}
