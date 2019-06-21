package tech.beshu.ror.integration

import java.time.Clock

import tech.beshu.ror.acl.Acl
import tech.beshu.ror.acl.factory.{CoreSettings, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.mocks.MockHttpClientsFactory
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.providers._
import tech.beshu.ror.utils.TestsUtils.BlockContextAssertion

trait BaseYamlLoadedAclTest extends BlockContextAssertion {

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
    implicit val propertiesProvider: PropertiesProvider = JvmPropertiesProvider
    new RawRorConfigBasedCoreFactory
  }
  val acl: Acl = factory
    .createCoreFrom(
      RawRorConfig.fromString(configYaml).right.get,
      MockHttpClientsFactory
    )
    .map {
      case Left(err) => throw new IllegalStateException(s"Cannot create ACL: $err")
      case Right(CoreSettings(aclEngine, _, _)) => aclEngine
    }
    .runSyncUnsafe()

  protected def configYaml: String
}
