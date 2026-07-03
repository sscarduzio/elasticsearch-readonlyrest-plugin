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
package tech.beshu.ror.benchmarks.acl

import cats.data.{NonEmptyList, NonEmptySet}
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.AccessControlList.RegularRequestResult
import tech.beshu.ror.accesscontrol.EnabledAccessControlList
import tech.beshu.ror.accesscontrol.EnabledAccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.audit.LoggingContext
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.ActionsRule
import tech.beshu.ror.accesscontrol.blocks.rules.http.{HeadersAndRule, MethodsRule}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.*
import tech.beshu.ror.syntax.*

import java.util.concurrent.TimeUnit

/**
 * Tier-1 KPI: end-to-end ACL evaluation (incl. the `doPrivileged` scheduler hop) over `blocks`
 * blocks of 4 cheap sync rules. `permitPath` matches in the last block; `denyPath` walks all blocks.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class AclEvaluationBenchmark {

  @Param(Array("10", "50", "100", "150"))
  var blocks: Int = scala.compiletime.uninitialized

  private implicit val loggingContext: LoggingContext = LoggingContext(Set.empty)

  private var acl: EnabledAccessControlList = scala.compiletime.uninitialized
  private var permitContext: NonIndexRequestContext = scala.compiletime.uninitialized
  private var denyContext: NonIndexRequestContext = scala.compiletime.uninitialized

  private def blockOf(i: Int): Block = {
    val rules = NonEmptyList.of[Rule](
      new AuthKeyRule(
        BasicAuthenticationRule.Settings(Credentials(User.Id(nes(s"user$i")), PlainTextSecret(nes(s"pass$i")))),
        CaseSensitivity.Enabled,
        Impersonation.Disabled
      ),
      new MethodsRule(MethodsRule.Settings(NonEmptySet.one(Method.GET))),
      new ActionsRule(ActionsRule.Settings(NonEmptySet.one(searchAction))),
      new HeadersAndRule(HeadersAndRule.Settings(NonEmptySet.one(
        AccessRequirement.MustBePresent(Header(Header.Name(nes("X-Custom-1")), nes("value-1")))
      )))
    )
    // .sorted = production RuleOrdering (what Block.createFrom applies); validation is config-time-only.
    new Block(Block.Name(s"block$i"), Block.Policy.Allow, Block.Verbosity.Info, Block.Audit.Enabled, rules.sorted)
  }

  @Setup(Level.Trial)
  def setup(): Unit = {
    val allBlocks = NonEmptyList.fromListUnsafe((1 to blocks).map(blockOf).toList)
    acl = new EnabledAccessControlList(
      allBlocks,
      new AccessControlListStaticContext(
        allBlocks,
        GlobalSettings(
          showBasicAuthPrompt = false,
          forbiddenRequestMessage = "forbidden",
          flsEngine = GlobalSettings.FlsEngine.ESWithLucene,
          settingsIndex = RorSettingsIndex(IndexName.Full(nes(".readonlyrest"))),
          userIdCaseSensitivity = CaseSensitivity.Enabled,
          usersDefinitionDuplicateUsernamesValidationEnabled = false
        ),
        Set.empty
      )
    )
    // Last block's credentials, so blocks 1..N-1 deny on auth and block N permits.
    permitContext = new NonIndexRequestContext(
      realisticHeaders(Credentials(User.Id(nes(s"user$blocks")), PlainTextSecret(nes(s"pass$blocks"))))
    )
    denyContext = new NonIndexRequestContext(
      realisticHeaders(Credentials(User.Id(nes("nobody")), PlainTextSecret(nes("nothing"))))
    )
    val permitResult = acl.handleRegularRequest(permitContext).runSyncUnsafe()._1
    require(
      permitResult match {
        case RegularRequestResult.Allowed(blockContext) => blockContext.block.name.value == s"block$blocks"
        case _ => false
      },
      s"expected permit in block$blocks, got: $permitResult"
    )
    val denyResult = acl.handleRegularRequest(denyContext).runSyncUnsafe()._1
    require(
      denyResult.isInstanceOf[RegularRequestResult.ForbiddenByMismatched[?]],
      s"expected deny, got: $denyResult"
    )
  }

  @Benchmark
  def permitPath(bh: Blackhole): Unit =
    bh.consume(acl.handleRegularRequest(permitContext).runSyncUnsafe())

  @Benchmark
  def denyPath(bh: Blackhole): Unit =
    bh.consume(acl.handleRegularRequest(denyContext).runSyncUnsafe())
}
