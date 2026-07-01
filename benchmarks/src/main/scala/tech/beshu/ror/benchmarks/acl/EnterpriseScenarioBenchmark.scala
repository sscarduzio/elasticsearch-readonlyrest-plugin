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
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{AnyOfGroupsRule, AuthKeyRule}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.ActionsRule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.rules.http.HeadersAndRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableGroupsLogic}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.matchers.RandomBasedUniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.util.concurrent.TimeUnit

/**
 * Tier-1 composite KPI ("worst real customer"): 100 blocks, each with a groups rule over 100
 * configured groups (user has 100 groups), an indices rule with 120 patterns, an actions rule
 * and a headers rule, evaluated against a realistic 20-header search request.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class EnterpriseScenarioBenchmark {

  private val blockCount = 100
  private val configuredGroupCount = 100
  private val userGroupCount = 100
  private val indexPatternCount = 120

  private implicit val loggingContext: LoggingContext = LoggingContext(Set.empty)

  private var acl: EnabledAccessControlList = scala.compiletime.uninitialized
  private var permitContext: IndexRequestContext = scala.compiletime.uninitialized
  private var denyContext: IndexRequestContext = scala.compiletime.uninitialized

  private def groupId(j: Int): GroupId = GroupId(nes(s"dept-$j"))

  private def groupsRuleOf(i: Int): AnyOfGroupsRule = {
    val permittedGroupIds = UniqueNonEmptyList.unsafeFrom(
      (0 until configuredGroupCount)
        .map(j => AlreadyResolved(NonEmptyList.one(groupId(j): GroupIdLike)): RuntimeMultiResolvableVariable[GroupIdLike])
    )
    val userDef = UserDef(
      usernames = UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(User.Id(nes(s"user$i"))))),
      mode = UserDef.Mode.WithoutGroupsMapping(
        new AuthKeyRule(
          BasicAuthenticationRule.Settings(Credentials(User.Id(nes(s"user$i")), PlainTextSecret(nes(s"pass$i")))),
          CaseSensitivity.Enabled,
          Impersonation.Disabled
        ),
        UniqueNonEmptyList.unsafeFrom((0 until userGroupCount).map(j => Group.from(groupId(j))))
      )
    )
    new AnyOfGroupsRule(
      BaseGroupsRule.Settings(
        new RuntimeResolvableGroupsLogic.Simple[GroupsLogic.AnyOf](permittedGroupIds),
        NonEmptyList.one(userDef)
      )
    )(CaseSensitivity.Enabled)
  }

  private def indicesRule(): IndicesRule = {
    val patterns = (0 until indexPatternCount)
      .map(j => AlreadyResolved(NonEmptyList.one(ClusterIndexName.fromString(s"logs-app-$j-*").get)): RuntimeMultiResolvableVariable[ClusterIndexName])
    new IndicesRule(
      IndicesRule.Settings(NonEmptySet.of(patterns.head, patterns.tail*), mustInvolveIndices = false),
      RandomBasedUniqueIdentifierGenerator
    )
  }

  private def actionsRule(): ActionsRule =
    new ActionsRule(ActionsRule.Settings(NonEmptySet.of(
      Action("indices:admin/aliases/get"), Action("indices:data/write/*"), searchAction
    )))

  private def headersRule(): HeadersAndRule =
    new HeadersAndRule(HeadersAndRule.Settings(NonEmptySet.of(
      AccessRequirement.MustBePresent(Header(Header.Name(nes("X-Custom-1")), nes("value-1"))),
      AccessRequirement.MustBePresent(Header(Header.Name(nes("X-Filler-1")), nes("value-1")))
    )))

  private def blockOf(i: Int): Block = {
    val rules = NonEmptyList.of[Rule](groupsRuleOf(i), actionsRule(), indicesRule(), headersRule())
    new Block(Block.Name(s"block$i"), Block.Policy.Allow, Block.Verbosity.Info, Block.Audit.Enabled, rules)
  }

  private def contextOf(credentials: Credentials): IndexRequestContext = {
    val requested = (0 until 5)
      .map(j => RequestedIndex(ClusterIndexName.fromString(s"logs-app-$j-2026").get, excluded = false))
      .toCovariantSet
    new IndexRequestContext(realisticHeaders(credentials), requested)
  }

  @Setup(Level.Trial)
  def setup(): Unit = {
    val allBlocks = NonEmptyList.fromListUnsafe((1 to blockCount).map(blockOf).toList)
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
    permitContext = contextOf(Credentials(User.Id(nes(s"user$blockCount")), PlainTextSecret(nes(s"pass$blockCount"))))
    denyContext = contextOf(Credentials(User.Id(nes("nobody")), PlainTextSecret(nes("nothing"))))

    val permitResult = acl.handleRegularRequest(permitContext).runSyncUnsafe()._1
    require(
      permitResult match {
        case RegularRequestResult.Allowed(blockContext) => blockContext.block.name.value == s"block$blockCount"
        case _ => false
      },
      s"expected permit in block$blockCount, got: $permitResult"
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
