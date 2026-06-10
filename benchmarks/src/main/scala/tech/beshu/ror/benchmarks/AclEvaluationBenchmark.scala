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
package tech.beshu.ror.benchmarks

import cats.data.{NonEmptyList, NonEmptySet, WriterT}
import cats.implicits.*
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import squants.information.Bytes
import tech.beshu.ror.accesscontrol.AccessControlList.RegularRequestResult
import tech.beshu.ror.accesscontrol.EnabledAccessControlList
import tech.beshu.ror.accesscontrol.EnabledAccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.History.{BlockHistory, RuleHistory}
import tech.beshu.ror.accesscontrol.audit.LoggingContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.ActionsRule
import tech.beshu.ror.accesscontrol.blocks.rules.http.{BaseHeaderRule, HeadersAndRule, MethodsRule}
import tech.beshu.ror.accesscontrol.blocks.{Block, Decision}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.accesscontrol.request.{RequestContext, RestRequest}
import tech.beshu.ror.es.EsServices
import tech.beshu.ror.syntax.*

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.collection.immutable.ListMap

/**
 * Quantifies the per-request scaffolding cost of ACL evaluation over 10 blocks of 4 cheap sync
 * rules (auth_key denies in blocks 1-9): the production WriterT path (post short-circuit), the
 * pre-short-circuit fold replica (what develop did), and a hand-rolled tail-recursive evaluator
 * with an explicit history accumulator and bare Task — the upper bound of a WriterT removal.
 * All evaluators return the same decision and equivalent history (asserted in setup). `permit`
 * matches in block 10; `deny` walks all 10 blocks without a match.
 *
 * `current_writerT` is the full production entrypoint and includes the `doPrivileged` async
 * boundary (`Task.executeOn`), a constant per-request scheduler hop the sync replicas do not
 * pay; `currentSync_writerT` runs the identical production scaffolding (the real
 * `Block.evaluateForRegularRequest` + the current ACL recursion) without that hop, so it is
 * the like-for-like baseline for the scaffolding comparison.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class AclEvaluationBenchmark {

  private type BC = GeneralNonIndexRequestBlockContext

  private implicit val loggingContext: LoggingContext = LoggingContext(Set.empty)

  private def nes(value: String): NonEmptyString = NonEmptyString.unsafeFrom(value)

  private val benchAction = Action("indices:data/read/search")

  private def blockOf(i: Int): Block = {
    val rules = NonEmptyList.of[Rule](
      new AuthKeyRule(
        BasicAuthenticationRule.Settings(Credentials(User.Id(nes(s"user$i")), PlainTextSecret(nes(s"pass$i")))),
        CaseSensitivity.Enabled,
        Impersonation.Disabled
      ),
      new MethodsRule(MethodsRule.Settings(NonEmptySet.one(Method.GET))),
      new ActionsRule(ActionsRule.Settings(NonEmptySet.one(benchAction))),
      new HeadersAndRule(BaseHeaderRule.Settings(NonEmptySet.one(
        AccessRequirement.MustBePresent(Header(Header.Name(nes("X-Custom-1")), nes("value-1")))
      )))
    )
    new Block(Block.Name(s"block$i"), Block.Policy.Allow, Block.Verbosity.Info, Block.Audit.Enabled, rules)
  }

  private val blocks: NonEmptyList[Block] = NonEmptyList.fromListUnsafe((1 to 10).map(blockOf).toList)

  private final class BenchRestRequest(override val allHeaders: Set[Header]) extends RestRequest {
    override val method: Method = Method.GET
    override val path: UriPath = UriPath.from("/idx/_search").get
    override val localAddress: Address = Address.from("127.0.0.1").get
    override val remoteAddress: Option[Address] = Address.from("127.0.0.1")
    override val content: String = ""
    override val contentLength: squants.information.Information = Bytes(0)
  }

  private final class BenchRequestContext(override val restRequest: RestRequest) extends RequestContext {
    override type BLOCK_CONTEXT = BC
    override def initialBlockContext(block: Block): BC =
      GeneralNonIndexRequestBlockContext(block, this, BlockMetadata.empty, Set.empty, List.empty)
    override val timestamp: Instant = Instant.now()
    override val taskId: Long = 0L
    override val id: RequestContext.Id = RequestContext.Id.fromString("acl-benchmark")
    override val rorKibanaSessionId: CorrelationId = CorrelationId.random
    override val `type`: Type = Type("SearchRequest")
    override val action: Action = benchAction
    override val requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]] = None
    override val indexAttributes: IndexAttributeFilter = IndexAttributeFilter.All
    override val esServices: EsServices = null
    override val isCompositeRequest: Boolean = false
    override val isAllowedForDLS: Boolean = true
  }

  private def requestContextWith(credentials: Credentials): RequestContext.Aux[BC] = {
    val headers =
      (1 to 18).map(i => Header(Header.Name(nes(s"X-Filler-$i")), nes(s"value-$i"))).toCovariantSet +
        Header(Header.Name(nes("X-Custom-1")), nes("value-1")) +
        BasicAuth.fromCredentials(credentials).header
    new BenchRequestContext(new BenchRestRequest(headers))
  }

  // Block 10's credentials, so blocks 1-9 deny on auth and block 10 permits.
  private val permitContext = requestContextWith(Credentials(User.Id(nes("user10")), PlainTextSecret(nes("pass10"))))
  private val denyContext = requestContextWith(Credentials(User.Id(nes("nobody")), PlainTextSecret(nes("nothing"))))

  // --- evaluator 1: the production path (current WriterT + short-circuit) ------------------------

  private val acl = new EnabledAccessControlList(
    blocks,
    new AccessControlListStaticContext(
      blocks,
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

  // --- evaluator 1b: current production scaffolding, measured without the doPrivileged hop ------

  // Verbatim production `executeBlocksForRegularRequest`: real current rule loop inside the block.
  private def currentExecuteBlock(block: Block, context: RequestContext.Aux[BC]): WriterT[Task, Vector[BlockHistory[BC]], Decision[BC]] =
    for {
      blockEvalDecision <- WriterT.liftF[Task, Vector[BlockHistory[BC]], (Decision[BC], BlockHistory[BC])](block.evaluateForRegularRequest(context))
      (decision, history) = blockEvalDecision
      aclProcessingResult <- oldLiftBlock(decision).tell(Vector(history))
    } yield aclProcessingResult

  private def currentSyncAcl(context: RequestContext.Aux[BC]): Task[(RegularRequestResult[BC], Vector[BlockHistory[BC]])] = {
    def executeBlocks(block: Block, remainingBlocks: List[Block]): WriterT[Task, Vector[BlockHistory[BC]], Decision[BC]] =
      currentExecuteBlock(block, context).flatMap {
        case permitted@Decision.Permitted(_) => oldLiftBlock(permitted)
        case denied@Decision.Denied(_) =>
          remainingBlocks match {
            case nextBlock :: rest => executeBlocks(nextBlock, rest)
            case Nil => oldLiftBlock(denied)
          }
      }

    executeBlocks(blocks.head, blocks.tail)
      .run
      .map { case (blocksHistory, result) => toRequestResult(result, blocksHistory) -> blocksHistory }
  }

  // --- evaluator 2: replica of the pre-short-circuit folds (develop before 4590a98d6) -----------

  private def oldLiftRule(task: Task[Decision[BC]]): WriterT[Task, Vector[RuleHistory[BC]], Decision[BC]] =
    WriterT.liftF[Task, Vector[RuleHistory[BC]], Decision[BC]](task)

  private def oldCheckRule(rule: Rule, blockContext: BC): WriterT[Task, Vector[RuleHistory[BC]], Decision[BC]] =
    oldLiftRule(rule.check[BC](blockContext).recover { case _ => Decision.Denied[BC](Cause.NotAuthorized) })
      .flatTap(decision => WriterT.tell(Vector(RuleHistory(rule.name, decision))))

  private def oldEvaluateBlock(block: Block, context: RequestContext.Aux[BC]): Task[(Decision[BC], BlockHistory[BC])] =
    block.rules.toList
      .foldLeft(oldLiftRule(Task.now(Decision.Permitted(context.initialBlockContext(block))))) {
        case (currentResult, rule) =>
          for {
            previousRulesResult <- currentResult
            resultAfterRulesCheck <- previousRulesResult match {
              case Decision.Permitted(blockContext) => oldCheckRule(rule, blockContext)
              case r@Decision.Denied(_) => oldLiftRule(Task.now(r))
            }
          } yield resultAfterRulesCheck
      }
      .run
      .map { case (history, result) =>
        result -> (result match {
          case d@Decision.Permitted(_) => BlockHistory.Permitted(block, d, history)
          case d@Decision.Denied(_) => BlockHistory.Denied(block, d, history)
        })
      }

  private def oldLiftBlock(result: Decision[BC]): WriterT[Task, Vector[BlockHistory[BC]], Decision[BC]] =
    WriterT.value[Task, Vector[BlockHistory[BC]], Decision[BC]](result)

  private def oldExecuteBlock(block: Block, context: RequestContext.Aux[BC]): WriterT[Task, Vector[BlockHistory[BC]], Decision[BC]] =
    for {
      blockEvalDecision <- WriterT.liftF[Task, Vector[BlockHistory[BC]], (Decision[BC], BlockHistory[BC])](oldEvaluateBlock(block, context))
      (decision, history) = blockEvalDecision
      aclProcessingResult <- oldLiftBlock(decision).tell(Vector(history))
    } yield aclProcessingResult

  private def oldEvaluateAcl(context: RequestContext.Aux[BC]): Task[(RegularRequestResult[BC], Vector[BlockHistory[BC]])] =
    blocks.tail
      .foldLeft(oldExecuteBlock(blocks.head, context)) { case (currentResult, block) =>
        for {
          prevBlocksExecutionResult <- currentResult
          newCurrentResult <- prevBlocksExecutionResult match {
            case Decision.Denied(_) => oldExecuteBlock(block, context)
            case Decision.Permitted(_) => oldLiftBlock(prevBlocksExecutionResult)
          }
        } yield newCurrentResult
      }
      .run
      .map { case (blocksHistory, result) => toRequestResult(result, blocksHistory) -> blocksHistory }

  // --- evaluator 3: hand-rolled tail-recursive evaluation, explicit accumulators, no WriterT ----

  private def handRolledBlock(block: Block, context: RequestContext.Aux[BC]): Task[(Decision[BC], BlockHistory[BC])] = {
    def loop(rules: List[Rule], blockContext: BC, acc: Vector[RuleHistory[BC]]): Task[(Decision[BC], Vector[RuleHistory[BC]])] =
      rules match {
        case Nil => Task.now((Decision.Permitted(blockContext), acc))
        case rule :: rest =>
          rule.check[BC](blockContext).flatMap { decision =>
            val newAcc = acc :+ RuleHistory(rule.name, decision)
            decision match {
              case Decision.Permitted(newBlockContext) => loop(rest, newBlockContext, newAcc)
              case d@Decision.Denied(_) => Task.now((d, newAcc))
            }
          }
      }

    loop(block.rules.toList, context.initialBlockContext(block), Vector.empty)
      .map { case (decision, ruleHistory) =>
        decision -> (decision match {
          case d@Decision.Permitted(_) => BlockHistory.Permitted(block, d, ruleHistory)
          case d@Decision.Denied(_) => BlockHistory.Denied(block, d, ruleHistory)
        })
      }
  }

  private def handRolledAcl(context: RequestContext.Aux[BC]): Task[(RegularRequestResult[BC], Vector[BlockHistory[BC]])] = {
    def loop(block: Block, rest: List[Block], acc: Vector[BlockHistory[BC]]): Task[(Decision[BC], Vector[BlockHistory[BC]])] =
      handRolledBlock(block, context).flatMap { case (decision, blockHistory) =>
        val newAcc = acc :+ blockHistory
        decision match {
          case p@Decision.Permitted(_) => Task.now((p, newAcc))
          case d@Decision.Denied(_) => rest match {
            case nextBlock :: more => loop(nextBlock, more, newAcc)
            case Nil => Task.now((d, newAcc))
          }
        }
      }

    loop(blocks.head, blocks.tail, Vector.empty)
      .map { case (decision, blocksHistory) => toRequestResult(decision, blocksHistory) -> blocksHistory }
  }

  // Same final outcome mapping as the production ACL, so all evaluators do equivalent total work.
  private def toRequestResult(decision: Decision[BC], blocksHistory: Vector[BlockHistory[BC]]): RegularRequestResult[BC] =
    decision match {
      case Decision.Permitted(blockContext) => RegularRequestResult.Allowed(blockContext)
      case Decision.Denied(_) =>
        RegularRequestResult.ForbiddenByMismatched(
          ListMap.from(blocksHistory.collect { case BlockHistory.Denied(block, d, _) => block.name -> d.cause })
        )
    }

  @Setup(Level.Trial)
  def assertAllEvaluatorsAgree(): Unit = {
    def matchedBlockName(result: RegularRequestResult[BC]): Option[String] = result match {
      case RegularRequestResult.Allowed(blockContext) => Some(blockContext.block.name.value)
      case _ => None
    }

    val permitResults = List(
      matchedBlockName(acl.handleRegularRequest(permitContext).runSyncUnsafe()._1),
      matchedBlockName(currentSyncAcl(permitContext).runSyncUnsafe()._1),
      matchedBlockName(oldEvaluateAcl(permitContext).runSyncUnsafe()._1),
      matchedBlockName(handRolledAcl(permitContext).runSyncUnsafe()._1)
    )
    require(permitResults.forall(_.contains("block10")), s"permit-path evaluators disagree: $permitResults")

    val denyResults = List(
      acl.handleRegularRequest(denyContext).runSyncUnsafe()._1,
      currentSyncAcl(denyContext).runSyncUnsafe()._1,
      oldEvaluateAcl(denyContext).runSyncUnsafe()._1,
      handRolledAcl(denyContext).runSyncUnsafe()._1
    )
    require(
      denyResults.forall(_.isInstanceOf[RegularRequestResult.ForbiddenByMismatched[BC]]),
      s"deny-path evaluators disagree: $denyResults"
    )

    val oldHistory = oldEvaluateAcl(permitContext).runSyncUnsafe()._2
    val handHistory = handRolledAcl(permitContext).runSyncUnsafe()._2
    require(
      oldHistory.map(_.history.size) == handHistory.map(_.history.size),
      "permit-path rule histories differ between old fold and hand-rolled evaluator"
    )
  }

  @Benchmark
  def current_writerT_permitPath(bh: Blackhole): Unit =
    bh.consume(acl.handleRegularRequest(permitContext).runSyncUnsafe())

  @Benchmark
  def current_writerT_denyPath(bh: Blackhole): Unit =
    bh.consume(acl.handleRegularRequest(denyContext).runSyncUnsafe())

  @Benchmark
  def currentSync_writerT_permitPath(bh: Blackhole): Unit =
    bh.consume(currentSyncAcl(permitContext).runSyncUnsafe())

  @Benchmark
  def currentSync_writerT_denyPath(bh: Blackhole): Unit =
    bh.consume(currentSyncAcl(denyContext).runSyncUnsafe())

  @Benchmark
  def oldPath_foldNoShortCircuit_permitPath(bh: Blackhole): Unit =
    bh.consume(oldEvaluateAcl(permitContext).runSyncUnsafe())

  @Benchmark
  def oldPath_foldNoShortCircuit_denyPath(bh: Blackhole): Unit =
    bh.consume(oldEvaluateAcl(denyContext).runSyncUnsafe())

  @Benchmark
  def handRolled_noWriterT_permitPath(bh: Blackhole): Unit =
    bh.consume(handRolledAcl(permitContext).runSyncUnsafe())

  @Benchmark
  def handRolled_noWriterT_denyPath(bh: Blackhole): Unit =
    bh.consume(handRolledAcl(denyContext).runSyncUnsafe())
}
