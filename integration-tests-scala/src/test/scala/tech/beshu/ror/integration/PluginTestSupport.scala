package tech.beshu.ror.integration

import tech.beshu.ror.utils.containers.generic.{CallingEsDirectly, EsWithRorPluginContainerCreator, TargetEsContainer}

trait PluginTestSupport extends EsWithRorPluginContainerCreator with CallingEsDirectly {
  this: TargetEsContainer =>
}
