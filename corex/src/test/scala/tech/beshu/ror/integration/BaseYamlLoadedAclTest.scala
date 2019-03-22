package tech.beshu.ror.integration

import java.time.Clock

import tech.beshu.ror.acl.Acl
import tech.beshu.ror.acl.factory.{CoreFactory, CoreSettings}
import tech.beshu.ror.acl.utils.{JavaEnvVarsProvider, JavaUuidProvider, StaticVariablesResolver, UuidProvider}
import tech.beshu.ror.mocks.MockHttpClientsFactory
import monix.execution.Scheduler.Implicits.global

trait BaseYamlLoadedAclTest {

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(JavaEnvVarsProvider)
    new CoreFactory
  }
  val acl: Acl = factory
    .createCoreFrom(configYaml, MockHttpClientsFactory)
    .map {
      case Left(err) => throw new IllegalStateException(s"Cannot create ACL: $err")
      case Right(CoreSettings(aclEngine, _, _)) => aclEngine
    }
    .runSyncUnsafe()

  protected def configYaml: String
}
