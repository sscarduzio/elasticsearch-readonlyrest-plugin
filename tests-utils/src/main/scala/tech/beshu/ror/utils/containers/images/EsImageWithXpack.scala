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
package tech.beshu.ror.utils.containers.images

import cats.data.NonEmptyList

object EsImageWithXpack extends EsImageWithXpack
class EsImageWithXpack extends EsImage {

  final case class Config(esVersion: String,
                          clusterName: String,
                          nodeName: String,
                          nodes: NonEmptyList[String])

  def create(config: Config): DockerImageDescription = {
    new EsImage()
      .create(
        config = toEsImageConfig(config),
        withEsConfigBuilder = updateEsConfig,
        withEsJavaOptsBuilder = identity
      )
  }

  private def updateEsConfig(builder: EsConfigBuilder): EsConfigBuilder = {
    builder
      .add("xpack.security.enabled: true")
      .add("xpack.ml.enabled: false")
      .add("xpack.security.transport.ssl.enabled: true")
      .add("xpack.security.transport.ssl.verification_mode: none")
      .add("xpack.security.transport.ssl.client_authentication: none")
      .add("xpack.security.transport.ssl.keystore.path: elastic-certificates.p12")
      .add("xpack.security.transport.ssl.truststore.path: elastic-certificates.p12")
  }

  private def toEsImageConfig(config: Config) = EsImage.Config(
    esVersion = config.esVersion,
    clusterName = config.clusterName,
    nodeName = config.nodeName,
    nodes = config.nodes
  )
}
