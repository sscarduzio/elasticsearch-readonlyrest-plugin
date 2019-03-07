package tech.beshu.ror.unit.acl.factory.decoders.definitions

import cats.implicits._
import cats.{Comonad, Functor}
import io.circe.DecodingFailure
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError
import tech.beshu.ror.acl.utils.ADecoder
import tech.beshu.ror.acl.utils.CirceOps.DecodingFailureOps

import scala.language.higherKinds

abstract class BaseDecoderTest[F[_] : Functor, A, B](decoder: ADecoder[F, A])
                                                    (implicit CM: Comonad[F])
  extends WordSpec with Inside {

  def assertDecodingSuccess(yaml: String,
                            assertion: A => Unit): Unit = {
    assertion {
      CM.extract(forceDecode(yaml))
    }
  }

  def assertDecodingFailure(yaml: String,
                            assertion: AclCreationError => Unit): Unit = {
    inside(CM.extract(decode(yaml))) { case Left(decodingFailure) =>
      decodingFailure.aclCreationError match {
        case Some(error) => assertion(error)
        case None => throw new IllegalStateException("Cannot find AclCreationError in decoding failure")
      }
    }
  }

  private def forceDecode(yaml: String): F[A] = {
    decode(yaml).map(_.fold(ex => throw ex, identity))
  }

  private def decode(yaml: String): F[Either[DecodingFailure, A]] = {
    io.circe.yaml.parser
      .parse(yaml)
      .map(json =>
        decoder
          .apply(json.hcursor)
      )
      .fold(ex => throw ex, identity)
  }

}
