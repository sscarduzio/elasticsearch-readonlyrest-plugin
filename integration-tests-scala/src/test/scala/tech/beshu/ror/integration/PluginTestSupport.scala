package tech.beshu.ror.integration

import tech.beshu.ror.utils.containers.generic.{CallingEsDirectly, EsClusterWithRorPluginProvider, TargetEsContainer}

trait PluginTestSupport extends EsClusterWithRorPluginProvider with CallingEsDirectly {
  this: TargetEsContainer =>
}
