package tech.beshu.ror.configuration.loader.distributed

import io.circe.generic.extras.Configuration

package object dto {
  implicit val configuration: Configuration = Configuration.default.withDiscriminator("type")

}
