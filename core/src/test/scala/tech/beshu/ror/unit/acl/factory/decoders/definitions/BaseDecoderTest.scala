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
package tech.beshu.ror.unit.acl.factory.decoders.definitions

import cats.implicits._
import cats.{Comonad, Functor}
import io.circe.DecodingFailure
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.Inside
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.utils.ADecoder
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecodingFailureOps
import tech.beshu.ror.utils.yaml

import scala.language.higherKinds

abstract class BaseDecoderTest[F[_] : Functor, A, B](decoder: ADecoder[F, A])
                                                    (implicit C: Comonad[F])
  extends AnyWordSpec with Inside {

  def assertDecodingSuccess(yaml: String,
                            assertion: A => Unit): Unit = {
    assertion {
      C.extract(forceDecode(yaml))
    }
  }

  def assertDecodingFailure(yaml: String,
                            assertion: AclCreationError => Unit): Unit = {
    inside(C.extract(decode(yaml))) { case Left(decodingFailure) =>
      decodingFailure.aclCreationError match {
        case Some(error) => assertion(error)
        case None => throw new IllegalStateException("Cannot find AclCreationError in decoding failure")
      }
    }
  }

  private def forceDecode(yaml: String): F[A] = {
    decode(yaml).map(_.fold(ex => throw ex, identity))
  }

  private def decode(yamlContent: String): F[Either[DecodingFailure, A]] = {
    yaml
      .parser
      .parse(yamlContent)
      .map(json =>
        decoder
          .apply(json.hcursor)
      )
      .fold(ex => throw ex, identity)
  }

}
