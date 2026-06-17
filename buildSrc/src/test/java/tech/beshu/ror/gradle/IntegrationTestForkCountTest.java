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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntegrationTestForkCountTest {

  @Test
  void explicitOverrideWinsOverEverything() {
    // operator knows their box: honour the number verbatim, ignore CPU/RAM/OS
    assertEquals(4, IntegrationTestForkCount.resolve("4", false, 2, 7.0));
    assertEquals(4, IntegrationTestForkCount.resolve("4", true, 2, 7.0));   // even on Windows
    assertEquals(8, IntegrationTestForkCount.resolve(" 8 ", false, 2, 7.0)); // trimmed
  }

  @Test
  void explicitOverrideIsFlooredAtOne() {
    assertEquals(1, IntegrationTestForkCount.resolve("0", false, 16, 64.0));
    assertEquals(1, IntegrationTestForkCount.resolve("-3", false, 16, 64.0));
  }

  @Test
  void blankOrUnsetOverrideFallsThroughToAutoScaling() {
    assertEquals(4, IntegrationTestForkCount.resolve(null, false, 4, 64.0));
    assertEquals(4, IntegrationTestForkCount.resolve("", false, 4, 64.0));
    assertEquals(4, IntegrationTestForkCount.resolve("   ", false, 4, 64.0));
  }

  @Test
  void nonNumericOverrideFailsLoudly() {
    assertThrows(IllegalArgumentException.class,
        () -> IntegrationTestForkCount.resolve("two", false, 4, 64.0));
  }

  @Test
  void windowsIsAlwaysOneWhenAutoScaling() {
    // fixed ES container ports collide across workers on Windows
    assertEquals(1, IntegrationTestForkCount.resolve(null, true, 16, 64.0));
  }

  @Test
  void hostedAgentTwoCpuSevenGbStaysAtOne() {
    // ubuntu-24.04 hosted agent: usable = 7 - 2 = 5g; 5 / 3.2 = 1 worker.
    // CPU would allow 2, RAM does not -> the tighter bound wins -> 1. No swap-induced flakiness.
    assertEquals(1, IntegrationTestForkCount.resolve(null, false, 2, 7.0));
  }

  @Test
  void ramIsTheBindingConstraintOnManyCoresLittleRam() {
    // 16 cores but only 8g: usable = 6g; 6 / 3.2 = 1 worker
    assertEquals(1, IntegrationTestForkCount.resolve(null, false, 16, 8.0));
    // 16 cores, 16g: usable = 14g; 14 / 3.2 = 4 workers (RAM-bound, below the 16-core CPU bound)
    assertEquals(4, IntegrationTestForkCount.resolve(null, false, 16, 16.0));
  }

  @Test
  void cpuIsTheBindingConstraintOnFewCoresLotsOfRam() {
    // 4 cores, 128g: RAM allows ~39 but only 4 cores -> CPU-bound -> 4
    assertEquals(4, IntegrationTestForkCount.resolve(null, false, 4, 128.0));
  }

  @Test
  void biggerSelfHostedBoxScalesUp() {
    // 8 cores, 32g: usable = 30g; 30 / 3.2 = 9 by RAM, capped by 8 cores -> 8
    assertEquals(8, IntegrationTestForkCount.resolve(null, false, 8, 32.0));
  }

  @Test
  void undetectableRamStaysConservative() {
    assertEquals(1, IntegrationTestForkCount.resolve(null, false, 16, 0.0));
    assertEquals(1, IntegrationTestForkCount.resolve(null, false, 16, -1.0));
  }

  @Test
  void zeroOrNegativeCpuIsTreatedAsOne() {
    // RAM allows plenty, but availableProcessors() reported nonsense -> floor CPU bound at 1
    assertEquals(1, IntegrationTestForkCount.resolve(null, false, 0, 64.0));
  }

  @Test
  void staticResolveIsClampedToMaxForks() {
    // 16 cores, 128g would naively give 16; clamp to MAX_FORKS=8 (image pre-build + ES bootstrap
    // stop paying off past this; the daemon becomes the bottleneck).
    assertEquals(8, IntegrationTestForkCount.resolve(null, false, 16, 128.0));
  }

  // ---- dynamic (live-signal) sizing -------------------------------------------------------------

  @Test
  void dynamicOverrideAndWindowsStillWin() {
    assertEquals(4, IntegrationTestForkCount.resolveDynamic("4", false, 8, 0.5, 64.0));
    assertEquals(1, IntegrationTestForkCount.resolveDynamic(null, true, 8, 0.5, 64.0));
  }

  @Test
  void dynamicSizesOffSPARECoresAndAVAILABLERam() {
    // idle 8-core box, 30g free: cpu spare = 8 - 0 = 8; ram = 30/3.2 = 9 -> min=8 (also MAX cap)
    assertEquals(8, IntegrationTestForkCount.resolveDynamic(null, false, 8, 0.0, 30.0));
    // same box at load 5: spare = 8 - 5 = 3 -> 3 workers (contention-aware)
    assertEquals(3, IntegrationTestForkCount.resolveDynamic(null, false, 8, 5.0, 30.0));
  }

  @Test
  void dynamicRyzenContendedByCoTenantCi() {
    // ryzen: 8 physical cores, but 5 ES agents + other IT legs already drive load ~10 and leave
    // ~12g free. spare cores = 8 - 10 -> floored to 1; ram = 12/3.2 = 3 -> min = 1. A loaded box
    // correctly throttles itself rather than piling on and failing (the N=5 failure we observed).
    assertEquals(1, IntegrationTestForkCount.resolveDynamic(null, false, 8, 10.0, 12.0));
    // same box quieter (load 3, 20g free): spare = 5, ram = 6 -> 5 workers.
    assertEquals(5, IntegrationTestForkCount.resolveDynamic(null, false, 8, 3.0, 20.0));
  }

  @Test
  void dynamicAvailableRamIsTheBinding() {
    // many idle cores but only 8g free right now -> 8/3.2 = 2 workers
    assertEquals(2, IntegrationTestForkCount.resolveDynamic(null, false, 16, 0.0, 8.0));
  }

  @Test
  void dynamicUndetectableSignalsStayConservative() {
    // cores unknown -> 1; load unknown -> don't subtract but cap at cores; ram unknown -> 1
    assertEquals(1, IntegrationTestForkCount.resolveDynamic(null, false, 0, 1.0, 64.0));
    assertEquals(1, IntegrationTestForkCount.resolveDynamic(null, false, 8, 1.0, 0.0));
    // load unknown (-1): cpu bound stays at cores(8); ram 30/3.2=9 -> min 8 (cap)
    assertEquals(8, IntegrationTestForkCount.resolveDynamic(null, false, 8, -1.0, 30.0));
  }
}
