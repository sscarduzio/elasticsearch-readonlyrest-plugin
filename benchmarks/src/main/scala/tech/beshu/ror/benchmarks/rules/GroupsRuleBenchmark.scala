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
package tech.beshu.ror.benchmarks.rules

import cats.data.NonEmptyList
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{AnyOfGroupsRule, AuthKeyRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableGroupsLogic}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.util.concurrent.TimeUnit

/**
 * Tier-1 KPI: per-request cost of one `groups` (any_of) rule check — group-ids resolution,
 * user-definition matching and basic-auth authentication — at field-max scale (100x100).
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class GroupsRuleBenchmark {

  @Param(Array("10", "100"))
  var configuredGroups: Int = scala.compiletime.uninitialized

  @Param(Array("5", "100"))
  var userGroups: Int = scala.compiletime.uninitialized

  private var rule: AnyOfGroupsRule = scala.compiletime.uninitialized
  private var blockContext: GeneralNonIndexRequestBlockContext = scala.compiletime.uninitialized

  private def groupId(j: Int): GroupId = GroupId(nes(s"dept-$j"))

  @Setup(Level.Trial)
  def setup(): Unit = {
    val permittedGroupIds = UniqueNonEmptyList.unsafeFrom(
      (0 until configuredGroups)
        .map(j => AlreadyResolved(NonEmptyList.one(groupId(j): GroupIdLike)): RuntimeMultiResolvableVariable[GroupIdLike])
    )
    val userDef = UserDef(
      usernames = UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(User.Id(nes("user1"))))),
      mode = UserDef.Mode.WithoutGroupsMapping(
        new AuthKeyRule(
          BasicAuthenticationRule.Settings(Credentials(User.Id(nes("user1")), PlainTextSecret(nes("pass1")))),
          CaseSensitivity.Enabled,
          Impersonation.Disabled
        ),
        UniqueNonEmptyList.unsafeFrom((0 until userGroups).map(j => Group.from(groupId(j))))
      )
    )
    rule = new AnyOfGroupsRule(
      BaseGroupsRule.Settings(
        new RuntimeResolvableGroupsLogic.Simple[GroupsLogic.AnyOf](permittedGroupIds),
        NonEmptyList.one(userDef)
      )
    )(CaseSensitivity.Enabled)

    val context = new NonIndexRequestContext(
      realisticHeaders(Credentials(User.Id(nes("user1")), PlainTextSecret(nes("pass1"))))
    )
    blockContext = context.initialBlockContext(noBlock)
    require(
      rule.check(blockContext).runSyncUnsafe().isInstanceOf[Decision.Permitted[?]],
      "expected the groups rule to match"
    )
  }

  @Benchmark
  def matchPath(bh: Blackhole): Unit =
    bh.consume(rule.check(blockContext).runSyncUnsafe())
}
