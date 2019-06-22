package tech.beshu.ror.acl.factory.consts

import better.files.File
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.providers.PropertiesProvider.PropName

object RorProperties {

  def readRorMetadataFlag(implicit propertiesProvider: PropertiesProvider): Boolean =
    propertiesProvider
      .getProperty(PropName(NonEmptyString.unsafeFrom("com.readonlyrest.kibana.metadata")))
      .forall(!"false".equalsIgnoreCase(_))

  def rorConfigCustomFile(implicit propertiesProvider: PropertiesProvider): Option[File] =
    propertiesProvider
      .getProperty(PropName(NonEmptyString.unsafeFrom("com.readonlyrest.settings.file.path")))
      .map(File(_))
}
