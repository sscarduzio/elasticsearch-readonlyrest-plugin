package tech.beshu.ror.integration.suites.base

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.Suite
import tech.beshu.ror.utils.containers.generic.providers.{MultipleClients, MultipleEsTargets, RorConfigFileNameProvider, SingleClient, SingleEsTarget}
import tech.beshu.ror.utils.containers.generic.{EsClusterProvider, EsContainerCreator}

object support {

  trait BaseIntegrationTest
    extends ForAllTestContainer
      with EsClusterProvider
      with RorConfigFileNameProvider {
    this: Suite with EsContainerCreator =>
  }

  trait SingleClientSupport extends SingleClient with SingleEsTarget
  trait MultipleClientsSupport extends MultipleClients with MultipleEsTargets
}
