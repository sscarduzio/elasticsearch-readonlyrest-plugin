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
import org.apache.logging.log4j.scala.Logging
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.AccessControl
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.{MutableMocksProviderWithCachePerRequest, NoOpMocksProvider}
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason
import tech.beshu.ror.accesscontrol.factory.{AsyncHttpClientsFactory, CoreFactory, CoreSettings, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.accesscontrol.logging.{AccessControlLoggingDecorator, AuditingTool, LoggingContext}
import tech.beshu.ror.boot.ReadonlyRest._
import tech.beshu.ror.configuration.ConfigLoading.{ErrorOr, LoadRorConfig}
import tech.beshu.ror.configuration.FipsConfiguration.FipsMode
import tech.beshu.ror.configuration.IndexConfigManager.SavingIndexConfigError
import tech.beshu.ror.configuration.RorProperties.RefreshInterval
import tech.beshu.ror.configuration._
import tech.beshu.ror.configuration.loader.{ConfigLoadingInterpreter, LoadRawRorConfig, LoadedRorConfig}
import tech.beshu.ror.es.{AuditSinkService, IndexJsonContentService}
import tech.beshu.ror.providers._

import java.security.Security
import scala.concurrent.duration._
import java.nio.file.Path
import java.time.Clock
import scala.language.{implicitConversions, postfixOps}

class ReadonlyRest(coreFactory: CoreFactory,
                   auditSinkCreator: AuditSinkCreator,
                   val indexConfigManager: IndexConfigManager,
                   val mocksProvider: MutableMocksProviderWithCachePerRequest,
                   val esConfigPath: Path)
                  (implicit scheduler: Scheduler,
                   envVarsProvider: EnvVarsProvider,
                   propertiesProvider: PropertiesProvider,
                   clock: Clock) extends Logging {

  def start(): Task[Either[StartingFailure, RorInstance]] = {
    (for {
      esConfig <- loadEsConfig()
      loadedRorConfig <- loadRorConfig(esConfig)
      instance <- startRor(esConfig, loadedRorConfig)
    } yield instance).value
  }

  private def loadEsConfig() = {
    val action = ConfigLoading.loadEsConfig(esConfigPath)
    runStartingFailureProgram(action)
  }

  private def loadRorConfig(esConfig: EsConfig) = {
    val action = LoadRawRorConfig.load(esConfigPath, esConfig, esConfig.rorIndex.index)
    runStartingFailureProgram(action)
  }

  private def runStartingFailureProgram[A](action: LoadRorConfig[ErrorOr[A]]) = {
    val compiler = ConfigLoadingInterpreter.create(indexConfigManager, RorProperties.rorIndexSettingLoadingDelay)
    EitherT(action.foldMap(compiler))
      .leftMap(toStartingFailure)
  }

  private def toStartingFailure(error: LoadedRorConfig.Error) = {
    error match {
      case LoadedRorConfig.FileParsingError(message) =>
        StartingFailure(message)
      case LoadedRorConfig.FileNotExist(path) =>
        StartingFailure(s"Cannot find settings file: ${path.value}")
      case LoadedRorConfig.EsFileNotExist(path) =>
        StartingFailure(s"Cannot find elasticsearch settings file: [${path.value}]")
      case LoadedRorConfig.EsFileMalformed(path, message) =>
        StartingFailure(s"Settings file is malformed: [${path.value}], $message")
      case LoadedRorConfig.IndexParsingError(message) =>
        StartingFailure(message)
      case LoadedRorConfig.IndexUnknownStructure =>
        StartingFailure(s"Settings index is malformed")
    }
  }

  private def startRor(esConfig: EsConfig,
                       loadedConfig: LoadedRorConfig[RawRorConfig]) = {
    for {
      engine <- EitherT(loadRorCore(loadedConfig.value, esConfig.rorIndex.index))
      rorInstance <- createRorInstance(esConfig.rorIndex.index, engine, loadedConfig)
    } yield rorInstance
  }

  private def createRorInstance(rorConfigurationIndex: RorConfigurationIndex,
                                engine: Engine,
                                loadedConfig: LoadedRorConfig[RawRorConfig]) = {
    EitherT.right[StartingFailure] {
      loadedConfig match {
        case LoadedRorConfig.FileConfig(config) =>
          RorInstance.createWithPeriodicIndexCheck(this, engine, config, rorConfigurationIndex)
        case LoadedRorConfig.ForcedFileConfig(config) =>
          RorInstance.createWithoutPeriodicIndexCheck(this, engine, config, rorConfigurationIndex)
        case LoadedRorConfig.IndexConfig(_, config) =>
          RorInstance.createWithPeriodicIndexCheck(this, engine, config, rorConfigurationIndex)
      }
    }
  }

  private[ror] def loadRorCore(config: RawRorConfig,
                               rorIndexNameConfiguration: RorConfigurationIndex): Task[Either[StartingFailure, Engine]] = {
    val httpClientsFactory = new AsyncHttpClientsFactory
    val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

    coreFactory
      .createCoreFrom(config, rorIndexNameConfiguration, httpClientsFactory, ldapConnectionPoolProvider, mocksProvider)
      .map { result =>
        result
          .right
          .map { coreSettings =>
            val engine = createEngine(httpClientsFactory, ldapConnectionPoolProvider, coreSettings)
            inspectFlsEngine(engine)
            engine
          }
          .left
          .map(handleLoadingCoreErrors)
      }
  }

  private def createEngine(httpClientsFactory: AsyncHttpClientsFactory,
                           ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                           coreSettings: CoreSettings) = {
    implicit val loggingContext: LoggingContext = LoggingContext(coreSettings.aclEngine.staticContext.obfuscatedHeaders)
    val auditingTool = createAuditingTool(coreSettings)
    val loggingDecorator = new AccessControlLoggingDecorator(
      underlying = coreSettings.aclEngine,
      auditingTool = auditingTool
    )

    new Engine(
      accessControl = loggingDecorator,
      httpClientsFactory = httpClientsFactory,
      ldapConnectionPoolProvider,
      auditingTool
    )
  }

  private def createAuditingTool(coreSettings: CoreSettings)
                                (implicit loggingContext: LoggingContext): Option[AuditingTool] = {
    coreSettings.auditingSettings
      .map(settings => new AuditingTool(settings, auditSinkCreator(settings.auditCluster)))
  }

  private def inspectFlsEngine(engine: Engine) = {
    engine.accessControl.staticContext.usedFlsEngineInFieldsRule.foreach {
      case FlsEngine.Lucene | FlsEngine.ESWithLucene =>
        logger.warn("Defined fls engine relies on lucene. To make it work well, all nodes should have ROR plugin installed.")
      case FlsEngine.ES =>
        logger.warn("Defined fls engine relies on ES only. This engine doesn't provide full FLS functionality hence some requests may be rejected.")
    }
  }

  private def handleLoadingCoreErrors(errors: NonEmptyList[RawRorConfigBasedCoreFactory.AclCreationError]) = {
    val errorsMessage = errors
      .map(_.reason)
      .map {
        case Reason.Message(msg) => msg
        case Reason.MalformedValue(yamlString) => s"Malformed settings: $yamlString"
      }
      .toList
      .mkString("Errors:\n", "\n", "")
    StartingFailure(errorsMessage)
  }
}

object ReadonlyRest {
  type AuditSinkCreator = AuditCluster => AuditSinkService

  final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

  sealed trait RorMode
  object RorMode {
    case object Plugin extends RorMode
    case object Proxy extends RorMode
  }

  final class Engine(val accessControl: AccessControl,
                     httpClientsFactory: AsyncHttpClientsFactory,
                     ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                     auditingTool: Option[AuditingTool])
                    (implicit scheduler: Scheduler) {

    private[ror] def shutdown(): Unit = {
      httpClientsFactory.shutdown()
      ldapConnectionPoolProvider.close().runAsyncAndForget
      auditingTool.foreach(_.close())
    }
  }

  def create(mode: RorMode,
             indexContentService: IndexJsonContentService,
             auditSinkCreator: AuditSinkCreator,
             esConfigPath: Path)
            (implicit scheduler: Scheduler,
             envVarsProvider: EnvVarsProvider,
             propertiesProvider: PropertiesProvider,
             clock: Clock): ReadonlyRest = {
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    val coreFactory: CoreFactory = new RawRorConfigBasedCoreFactory(mode)

    create(coreFactory, indexContentService, auditSinkCreator, esConfigPath)
  }

  def create(coreFactory: CoreFactory,
             indexContentService: IndexJsonContentService,
             auditSinkCreator: AuditSinkCreator,
             esConfigPath: Path)
            (implicit scheduler: Scheduler,
             envVarsProvider: EnvVarsProvider,
             propertiesProvider: PropertiesProvider,
             clock: Clock): ReadonlyRest = {
    val indexConfigManager: IndexConfigManager = new IndexConfigManager(indexContentService)
    val mocksProvider = new MutableMocksProviderWithCachePerRequest(NoOpMocksProvider)

    new ReadonlyRest(coreFactory, auditSinkCreator, indexConfigManager, mocksProvider, esConfigPath)
  }
}