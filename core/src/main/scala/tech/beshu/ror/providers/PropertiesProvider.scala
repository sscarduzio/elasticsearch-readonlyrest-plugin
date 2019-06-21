package tech.beshu.ror.providers

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.providers.PropertiesProvider.PropName

import scala.util.Try

trait PropertiesProvider {
  def getProperty(name: PropName): Option[String]
}

object PropertiesProvider {

  final case class PropName(value: NonEmptyString)
}

object JvmPropertiesProvider extends PropertiesProvider {
  override def getProperty(name: PropName): Option[String] =
    Try(Option(System.getProperty(name.value.value))).toOption.flatten
}


