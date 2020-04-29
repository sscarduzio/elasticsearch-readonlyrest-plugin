/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.configuration.loader

import io.circe.Codec
import io.circe.generic.extras.semiauto.deriveUnwrappedCodec
import io.circe.generic.semiauto
import io.circe.shapes._
package object distribuated {

  import io.circe.generic.auto._

  implicit lazy val codecLoadedConfigError: Codec[LoadedConfig.Error] = semiauto.deriveCodec
  implicit lazy val codecLoadedConfig: Codec[LoadedConfig[String]] = semiauto.deriveCodec
  implicit lazy val codecNodeConfigRequest: Codec[NodeConfigRequest] = semiauto.deriveCodec
  implicit lazy val codecPath: Codec[Path] = deriveUnwrappedCodec

}
