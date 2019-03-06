package tech.beshu.ror.unit.acl.factory.decoders.definitions

import cats.implicits._
import cats.Functor
import org.scalatest.WordSpec
import tech.beshu.ror.acl.utils.ADecoder

import scala.language.higherKinds

abstract class BaseDecoderTest[F[_] : Functor, A](decoder: ADecoder[F, A]) extends WordSpec {

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
