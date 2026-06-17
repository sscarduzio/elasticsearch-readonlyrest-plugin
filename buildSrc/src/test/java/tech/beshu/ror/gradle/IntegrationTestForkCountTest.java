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

  // ---- per-agent sizing (deterministic box partition; no load feedback) ------------------------

  @Test
  void perAgentOverrideAndWindowsStillWin() {
    assertEquals(4, IntegrationTestForkCount.resolvePerAgent("4", false, 8, 64.0, 2));
    assertEquals(1, IntegrationTestForkCount.resolvePerAgent(null, true, 8, 64.0, 2));
  }

  @Test
  void perAgentRyzenTwoAgentsGetsFourForksEach() {
    // ryzen: 8 physical cores, 62g, 2 agents on the host. cpu = 8/2 = 4; ram = (62-2)/2/3.2 = 9 ->
    // min = 4. The fix: deterministic, NOT collapsed to 1 by self-inflicted load (the old bug).
    assertEquals(4, IntegrationTestForkCount.resolvePerAgent(null, false, 8, 62.0, 2));
  }

  @Test
  void perAgentMoreAgentsMeansFewerForksEach() {
    // same box, 5 agents: cpu = 8/5 = 1; ram = (62-2)/5/3.2 = 3 -> min 1. Shows WHY we cut to 2 agents.
    assertEquals(1, IntegrationTestForkCount.resolvePerAgent(null, false, 8, 62.0, 5));
    // 4 agents: cpu = 8/4 = 2 -> 2 forks each
    assertEquals(2, IntegrationTestForkCount.resolvePerAgent(null, false, 8, 62.0, 4));
  }

  @Test
  void perAgentRamCanBeTheBinding() {
    // 8 cores but only 16g total, 2 agents: cpu = 4; ram = (16-2)/2/3.2 = 2 -> min 2 (RAM-bound)
    assertEquals(2, IntegrationTestForkCount.resolvePerAgent(null, false, 8, 16.0, 2));
  }

  @Test
  void perAgentSingleAgentGetsWholeBoxUpToCap() {
    // 1 agent on 16-core/128g: cpu = 16, ram plenty -> clamp to MAX_FORKS=8
    assertEquals(8, IntegrationTestForkCount.resolvePerAgent(null, false, 16, 128.0, 1));
  }

  @Test
  void perAgentDegenerateInputsHandled() {
    assertEquals(1, IntegrationTestForkCount.resolvePerAgent(null, false, 0, 64.0, 2));  // cores unknown -> 1
    assertEquals(1, IntegrationTestForkCount.resolvePerAgent(null, false, 8, 0.0, 2));   // ram unknown -> 1
    // agents<1 is treated as 1 (whole box): cpu=8, ram=(62-2)/3.2=18 -> min 8 -> clamp 8
    assertEquals(8, IntegrationTestForkCount.resolvePerAgent(null, false, 8, 62.0, 0));
  }
}
