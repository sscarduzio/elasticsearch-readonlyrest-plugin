package tech.beshu.ror.unit.utils

import tech.beshu.ror.providers.PropertiesProvider

class TestsPropertiesProvider(propertiesMap: Map[PropertiesProvider.PropName, String]) extends PropertiesProvider {
  override def getProperty(name: PropertiesProvider.PropName): Option[String] = propertiesMap.get(name)
}
object TestsPropertiesProvider {
  def default: TestsPropertiesProvider = new TestsPropertiesProvider(Map.empty)
}
