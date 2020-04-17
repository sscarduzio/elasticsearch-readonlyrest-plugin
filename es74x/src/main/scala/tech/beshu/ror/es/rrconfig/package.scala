package tech.beshu.ror.es

import io.circe.Codec
import io.circe.generic.extras.semiauto.deriveUnwrappedCodec
import io.circe.generic.semiauto
import io.circe.shapes._

package object rrconfig {

  import io.circe.generic.auto._

  implicit lazy val codecLoadedConfigError: Codec[LoadedConfig.Error] = semiauto.deriveCodec
  implicit lazy val codecLoadedConfig: Codec[LoadedConfig] = semiauto.deriveCodec
  implicit lazy val codecNodeConfigRequest: Codec[NodeConfigRequest] = semiauto.deriveCodec
  implicit lazy val codecPath: Codec[Path] = deriveUnwrappedCodec

}
