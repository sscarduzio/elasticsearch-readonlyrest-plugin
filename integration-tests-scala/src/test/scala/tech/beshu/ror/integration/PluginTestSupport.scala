package tech.beshu.ror.integration

import tech.beshu.ror.utils.containers.generic.{CallingEsDirectly, EsContainerWithRorPluginCreator, TargetEsContainer}

trait PluginTestSupport extends EsContainerWithRorPluginCreator with CallingEsDirectly {
  this: TargetEsContainer =>
}
