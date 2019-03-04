package tech.beshu.ror.unit.acl.factory.decoders.definitions

import cats.implicits._
import cats.Functor
import tech.beshu.ror.acl.utils.ADecoder

abstract class BaseDecoderTest[F[_] : Functor, A](decoder: ADecoder[F, A]) {

  def decode(yaml: String): F[A] = {
    io.circe.yaml.parser
      .parse(yaml)
      .map(json =>
        decoder
          .apply(json.hcursor)
          .map(_.fold(ex => throw ex, identity))
      )
      .fold(ex => throw ex, identity)
  }
}
