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
package tech.beshu.ror.boot

import cats.data.{EitherT, NonEmptyList}
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicBoolean
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditingConfig
import tech.beshu.ror.accesscontrol.audit.sink.AuditSinkServiceCreator
import tech.beshu.ror.accesscontrol.audit.{AuditingTool, LoggingContext}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.{AuthServicesMocks, MutableMocksProviderWithCachePerRequest}
import tech.beshu.ror.accesscontrol.domain.{RequestId, RorSettingsIndex}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.{Core, CoreFactory, HttpClientsFactory, RawRorSettingsBasedCoreFactory}
import tech.beshu.ror.accesscontrol.logging.AccessControlListLoggingDecorator
import tech.beshu.ror.boot.ReadonlyRest.*
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.es.services.IndexDocumentManager
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.es.*
import tech.beshu.ror.settings.ror.{MainRorSettings, RawRorSettings, TestRorSettings}
import tech.beshu.ror.utils.RefinedUtils.PositiveFiniteDuration
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.ScalaOps.{RetryPolicy, retryUntilSuccessful}

import java.time.Instant
import scala.concurrent.duration.*
import scala.language.postfixOps

class ReadonlyRest(
    coreFactory: CoreFactory,
    indexDocumentManager: IndexDocumentManager,
    auditSinkServiceCreator: AuditSinkServiceCreator
)(
    implicit systemContext: SystemContext
) extends RequestIdAwareLogging {

  import systemContext.scheduler

  private[boot] val authServicesMocksProvider = new MutableMocksProviderWithCachePerRequest(AuthServicesMocks.empty)

  def start(esConfigBasedRorSettings: EsConfigBasedRorSettings): Task[Either[StartingFailure, RorInstance]] = {
    implicit val requestId: RequestId = RequestId(systemContext.uuidProvider.random.toString)
    (for {
      creatorsAndLoaders <- lift(
        SettingsRelatedCreatorsAndLoaders.create(esConfigBasedRorSettings, indexDocumentManager)
      )
      loadedSettings <- EitherT(creatorsAndLoaders.startingRorSettingsLoader.load()).leftMap(StartingFailure(_))
      (loadedMainRorSettings, loadedTestRorSettings) = loadedSettings
      instance <- startRor(
        esConfigBasedRorSettings,
        creatorsAndLoaders.creators,
        loadedMainRorSettings,
        loadedTestRorSettings
      )
    } yield instance).value
  }

  /**
   * Starts ROR, retrying indefinitely until it succeeds. Every starting failure is transient from the ES node point of
   * view - the settings index may not be readable yet, the master node may not be elected yet - so there is nothing to
   * be gained by giving up.
   */
  def startWithRetry(
      esConfigBasedRorSettings: EsConfigBasedRorSettings,
      retryPolicy: RetryPolicy = defaultStartingRetryPolicy
  )(onFailedAttempt: StartingFailure => Unit): Task[RorInstance] = {
    implicit val requestId: RequestId = RequestId(systemContext.uuidProvider.random.toString)
    retryUntilSuccessful[StartingFailure, RorInstance](
      policy = retryPolicy,
      onFailedAttempt = (failure, attempt) =>
        Task.delay {
          onFailedAttempt(failure)
          logger.warn(
            s"ReadonlyREST starting attempt ${attempt.number} failed (ReadonlyREST has not been able to start for " +
              s"${attempt.elapsed.toCoarsest}). It will try to start again in ${attempt.nextAttemptDelay} ..."
          )
        }
    ) {
      start(esConfigBasedRorSettings)
        .onErrorHandle(ex => Left(StartingFailure("Cannot start ReadonlyREST", Some(ex))))
    }
  }

  private def startRor(
      esConfigBasedRorSettings: EsConfigBasedRorSettings,
      creators: SettingsRelatedCreators,
      loadedMainRorSettings: MainRorSettings,
      loadedTestRorSettings: Option[TestRorSettings]
  )(
      implicit requestId: RequestId
  ) = {
    for {
      mainEngine <- EitherT(
        loadRorEngine(loadedMainRorSettings.rawSettings, esConfigBasedRorSettings.settingsSource.settingsIndex)
      )
      testEngine <- EitherT.right(
        loadTestEngine(loadedTestRorSettings, esConfigBasedRorSettings.settingsSource.settingsIndex)
      )
      rorInstance <- createRorInstance(
        esConfigBasedRorSettings,
        creators,
        mainEngine,
        testEngine,
        loadedMainRorSettings
      )
    } yield rorInstance
  }

  private def loadTestEngine(loadedTestRorSettings: Option[TestRorSettings], settingsIndex: RorSettingsIndex)(
      implicit requestId: RequestId
  ) = {
    loadedTestRorSettings match {
      case None =>
        Task.now(TestEngine.NotConfigured)
      case Some(settings) if !settings.isExpired(systemContext.clock) =>
        loadActiveTestEngine(settingsIndex, settings)
      case Some(settings) =>
        loadInvalidatedTestEngine(settings)
    }
  }

  private def loadActiveTestEngine(settingsIndex: RorSettingsIndex, testSettings: TestRorSettings)(
      implicit requestId: RequestId
  ) = {
    for {
      _ <- Task.delay(authServicesMocksProvider.update(testSettings.mocks))
      testEngine <- loadRorEngine(testSettings.rawSettings, settingsIndex)
        .map {
          case Right(loadedEngine) =>
            TestEngine.Configured(
              engine = loadedEngine,
              settings = testSettings.rawSettings,
              expiration = TestEngine.Expiration(testSettings.expiration.ttl, testSettings.expiration.validTo)
            )
          case Left(startingFailure) =>
            logger.error(
              s"Unable to start test engine. Cause: ${startingFailure.message.show}. Test settings engine will be marked as invalidated."
            )
            invalidatedTestEngine(testSettings)
        }
    } yield testEngine
  }

  private def loadInvalidatedTestEngine(testSettings: TestRorSettings) = {
    Task
      .delay(authServicesMocksProvider.update(testSettings.mocks))
      .map { case () => invalidatedTestEngine(testSettings) }
  }

  private def invalidatedTestEngine(testSettings: TestRorSettings) = {
    TestEngine.Invalidated(
      testSettings.rawSettings,
      TestEngine.Expiration(testSettings.expiration.ttl, testSettings.expiration.validTo)
    )
  }

  private def createRorInstance(
      esConfigBasedRorSettings: EsConfigBasedRorSettings,
      creators: SettingsRelatedCreators,
      mainEngine: Engine,
      testEngine: TestEngine,
      alreadyLoadedSettings: MainRorSettings
  ) = {
    EitherT.right[StartingFailure] {
      RorInstance.create(
        this,
        esConfigBasedRorSettings,
        creators,
        MainEngine(mainEngine, alreadyLoadedSettings.rawSettings),
        testEngine
      )
    }
  }

  private[ror] def loadRorEngine(settings: RawRorSettings, settingsIndex: RorSettingsIndex)(
      implicit requestId: RequestId
  ): Task[Either[StartingFailure, Engine]] = {
    val engineResources = EngineResources.create()

    EitherT(
      coreFactory
        .createCoreFrom(
          settings,
          settingsIndex,
          engineResources.httpClientsFactory,
          engineResources.ldapConnectionPoolProvider,
          authServicesMocksProvider
        )
    )
      .flatMap(core => createEngine(engineResources, core))
      .semiflatTap { engine =>
        Task(inspectFlsEngine(engine))
      }
      .leftMap(handleLoadingCoreErrors)
      .value
      // when no engine is created, nobody takes over the ownership of the resources, so they have to be released here -
      // otherwise each starting attempt would leak an HTTP client and an LDAP connection pool
      .flatMap {
        case result @ Right(_) => Task.now(result)
        case result @ Left(_)  => engineResources.release().map(_ => result)
      }
      .onErrorHandleWith { ex =>
        engineResources.release().flatMap(_ => Task.raiseError(ex))
      }
      .doOnCancel(engineResources.release())
  }

  private def createEngine(
      engineResources: EngineResources,
      core: Core
  ): EitherT[Task, NonEmptyList[CoreCreationError], Engine] = {
    implicit val loggingContext: LoggingContext = LoggingContext(core.accessControl.staticContext.obfuscatedHeaders)
    EitherT(createAuditingTool(core.auditingConfig))
      .map { auditingTool =>
        val decoratedCore = Core(
          accessControl = new AccessControlListLoggingDecorator(
            underlying = core.accessControl.withBlockTransformation(_.withResolvedAuditSinks(auditingTool.sinks)),
            auditingTool = auditingTool
          ),
          dependencies = core.dependencies,
          auditingConfig = core.auditingConfig
        )
        new Engine(
          core = decoratedCore,
          engineResources = engineResources,
          auditingTool = auditingTool
        )
      }
  }

  private def createAuditingTool(auditingConfig: AuditingConfig)(
      implicit loggingContext: LoggingContext
  ): Task[Either[NonEmptyList[CoreCreationError], AuditingTool]] = {
    AuditingTool
      .create(auditingConfig, auditSinkServiceCreator)(
        using systemContext.clock,
        loggingContext
      )
      .map {
        _.leftMap {
          _.map(creationError => CoreCreationError.AuditingSettingsCreationError(Message(creationError.message)))
        }
      }
  }

  private def inspectFlsEngine(engine: Engine)(
      implicit requestId: RequestId
  ): Unit = {
    engine.core.accessControl.staticContext.usedFlsEngineInFieldsRule.foreach {
      case FlsEngine.Lucene | FlsEngine.ESWithLucene =>
        logger.warn(
          "Defined fls engine relies on lucene. To make it work well, all nodes should have ROR plugin installed."
        )
      case FlsEngine.ES =>
        logger.warn(
          "Defined fls engine relies on ES only. This engine doesn't provide full FLS functionality hence some requests may be rejected."
        )
    }
  }

  private def handleLoadingCoreErrors(errors: NonEmptyList[RawRorSettingsBasedCoreFactory.CoreCreationError]) = {
    val errorsMessage = errors
      .map(_.reason)
      .map {
        case Reason.Message(msg)               => msg
        case Reason.MalformedValue(yamlString) => s"Malformed settings: ${yamlString.show}"
      }
      .toList
      .mkString("Errors:\n", "\n", "")
    StartingFailure(errorsMessage)
  }

  private def lift[A](value: => A) = {
    EitherT.liftF(Task.delay(value))
  }

}

object ReadonlyRest {

  final case class MainEngine(engine: Engine, settings: RawRorSettings)

  sealed trait TestEngine

  object TestEngine {
    object NotConfigured extends TestEngine

    final case class Configured(engine: Engine, settings: RawRorSettings, expiration: Expiration) extends TestEngine

    final case class Invalidated(settings: RawRorSettings, expiration: Expiration) extends TestEngine

    final case class Expiration(ttl: PositiveFiniteDuration, validTo: Instant)
  }

  private[boot] final class EngineResources private (
      val httpClientsFactory: HttpClientsFactory,
      val ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider
  ) {

    private val released = AtomicBoolean(false)

    def release(): Task[Unit] = Task.defer {
      if (released.compareAndSet(expect = false, update = true)) {
        httpClientsFactory
          .shutdown()
          .flatMap(_ => ldapConnectionPoolProvider.close())
      } else {
        Task.unit
      }
    }

  }

  private[boot] object EngineResources {

    def create(): EngineResources = new EngineResources(
      httpClientsFactory = HttpClientsFactory.default(),
      ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider
    )

  }

  final class Engine private[boot] (
      val core: Core,
      engineResources: EngineResources,
      auditingTool: AuditingTool
  )(
      implicit scheduler: Scheduler
  ) {

    private[ror] def shutdown(): Unit = {
      engineResources.release().runAsyncAndForget
      auditingTool.close().runAsyncAndForget
    }

  }

  final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

  private val defaultStartingRetryPolicy: RetryPolicy = RetryPolicy(initialDelay = 5 seconds, maxDelay = 1 minute)

  def create(indexContentService: IndexDocumentManager, auditSinkServiceCreator: AuditSinkServiceCreator, env: EsEnv)(
      implicit systemContext: SystemContext
  ): ReadonlyRest = {
    val coreFactory: CoreFactory = new RawRorSettingsBasedCoreFactory(env)
    create(coreFactory, indexContentService, auditSinkServiceCreator)
  }

  def create(
      coreFactory: CoreFactory,
      indexDocumentManager: IndexDocumentManager,
      auditSinkServiceCreator: AuditSinkServiceCreator
  )(
      implicit systemContext: SystemContext
  ): ReadonlyRest = {
    new ReadonlyRest(coreFactory, indexDocumentManager, auditSinkServiceCreator)
  }

}
