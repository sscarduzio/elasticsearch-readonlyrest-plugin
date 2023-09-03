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
package tech.beshu.ror.accesscontrol.utils

import cats.implicits._
import cats.{Functor, Id}
import io.circe._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.utils.yaml.YamlOps

import scala.annotation.tailrec

sealed abstract class ADecoder[F[_] : Functor, A] {

  type DECODER[T] <: ADecoder[F, T]
  type CREATOR <: ADecoderCreator[F, DECODER]

  val creator: CREATOR

  def apply(c: HCursor): F[Either[DecodingFailure, A]]

  def tryDecode(c: ACursor): F[Either[DecodingFailure, A]]

  def map[B](f: A => B): DECODER[B]

  def emap[B](f: A => Either[String, B]): DECODER[B]

  def flatMap[B](f: A => DECODER[B]): DECODER[B]

  type Element = Json
  type Context = String

  def withError(error: => CoreCreationError): DECODER[A] = {
    creator.instance { c =>
      apply(c).map(_.left.map(_.overrideDefaultErrorWith(error)))
    }
  }

  def withError(newErrorCreator: Reason => CoreCreationError, defaultErrorReason: => Reason): DECODER[A] = {
    creator.instance { c =>
      apply(c).map(_.left.map { df =>
        val error = df.aclCreationError.map(e => newErrorCreator(e.reason)) match {
          case Some(newError) => newError
          case None => newErrorCreator(defaultErrorReason)
        }
        df.withMessage(AclCreationErrorCoders.stringify(error))
      })
    }
  }

  def withErrorFromCursor(error: (Element, Context) => CoreCreationError): DECODER[A] = {
    creator.instance { c =>
      val element = c.value
      val context = YamlOps.jsonToYamlString(c.up.focus.get).trim
      apply(c).map(_.left.map(_.overrideDefaultErrorWith(error(element, context))))
    }
  }

  def withErrorFromJson(errorCreator: Json => CoreCreationError): DECODER[A] = {
    creator.instance { c =>
      apply(c).map(_.left.map(_.overrideDefaultErrorWith(errorCreator(c.value))))
    }
  }

  def mapError(newErrorCreator: Reason => CoreCreationError): DECODER[A] =
    creator.instance { c =>
      apply(c).map(_.left.map { df =>
        df.aclCreationError.map(e => newErrorCreator(e.reason)) match {
          case Some(newError) => df.withMessage(AclCreationErrorCoders.stringify(newError))
          case None => df
        }
      })
    }

  def emapE[B](f: A => Either[CoreCreationError, B]): DECODER[B] =
    emap { a => f(a).left.map(AclCreationErrorCoders.stringify) }
}

trait ADecoderCreator[F[_], DECODER[_] <: ADecoder[F, _]] {

  def instance[A](f: HCursor => F[Either[DecodingFailure, A]]): DECODER[A]

  def from[A](decoder: Decoder[A]): DECODER[A]

  def list[A](implicit decoder: ADecoder[F, A]): DECODER[List[A]]
}

sealed abstract class AsyncDecoder[A] extends ADecoder[Task, A] {

  override type DECODER[T] = AsyncDecoder[T]
  override type CREATOR = AsyncDecoderCreator.type

  override val creator: AsyncDecoderCreator.type = AsyncDecoderCreator

  override def apply(c: HCursor): Task[Either[DecodingFailure, A]]

  override def tryDecode(c: ACursor): Task[Either[DecodingFailure, A]] = {
    c match {
      case hc: HCursor => apply(hc)
      case _ => Task.now(Left(
        DecodingFailure("Attempt to decode value on failed cursor", c.history)
      ))
    }
  }

  override def map[B](f: A => B): AsyncDecoder[B] = {
    AsyncDecoderCreator.instance { c =>
      tryDecode(c).map {
        case Right(a) => Right(f(a))
        case l@Left(_) => l.asInstanceOf[Decoder.Result[B]]
      }
    }
  }

  override def emap[B](f: A => Either[String, B]): AsyncDecoder[B] = {
    AsyncDecoderCreator.instance { c =>
      tryDecode(c).map {
        case Right(a) => f(a) match {
          case r@Right(_) => r.asInstanceOf[Decoder.Result[B]]
          case Left(message) => Left(DecodingFailure(message, c.history))
        }
        case l@Left(_) => l.asInstanceOf[Decoder.Result[B]]
      }
    }
  }

  override def flatMap[B](f: A => AsyncDecoder[B]): AsyncDecoder[B] = {
    AsyncDecoderCreator.instance { c =>
      tryDecode(c).flatMap {
        case Right(a) => f(a).tryDecode(c)
        case l@Left(_) => Task.now(l.asInstanceOf[Decoder.Result[B]])
      }
    }
  }
}

object AsyncDecoderCreator extends ADecoderCreator[Task, AsyncDecoder] {

  def from[A](value: => Task[A]): AsyncDecoder[A] = new AsyncDecoder[A] {
    override def apply(c: HCursor): Task[Either[DecodingFailure, A]] = {
      value.map(Right.apply)
    }
  }

  def from[A](decoder: ADecoder[Id, A]): AsyncDecoder[A] = new AsyncDecoder[A] {
    override def apply(c: HCursor): Task[Either[DecodingFailure, A]] = Task(decoder.apply(c))
  }

  override def instance[A](f: HCursor => Task[Either[DecodingFailure, A]]): AsyncDecoder[A] = new AsyncDecoder[A] {
    override def apply(c: HCursor): Task[Either[DecodingFailure, A]] = f(c)
  }

  override def from[A](decoder: Decoder[A]): AsyncDecoder[A] = new AsyncDecoder[A] {
    override def apply(c: HCursor): Task[Either[DecodingFailure, A]] = Task(decoder.apply(c))
  }

  override def list[A](implicit decoder: ADecoder[Task, A]): AsyncDecoder[List[A]] = new AsyncDecoder[List[A]] {

    override def apply(c: HCursor): Task[Either[DecodingFailure, List[A]]] = {
      if (c.downArray.succeeded) {
        cursors(c.downArray)
          .foldLeft(List.newBuilder[Task[Either[DecodingFailure, A]]]) {
            case (acc, current) => acc += decoder(current.asInstanceOf[HCursor])
          }
          .result()
          .sequence
          .map { decodingResults =>
            val failures = decodingResults.collect { case Left(error) => error }
            failures match {
              case Nil => Right(decodingResults.collect { case Right(value) => value })
              case error :: _ => Left(error)
            }
          }
      } else {
        if (c.value.isArray) Task.now(Right(List.newBuilder[A].result()))
        else Task.now(Left(DecodingFailure("C[A]", c.history)))
      }
    }

    def cursors(current: ACursor): Vector[ACursor] = {
      @tailrec
      def collectCursor(cursor: ACursor, foundCursors: Vector[ACursor]): Vector[ACursor] = {
        if (!cursor.succeeded) foundCursors
        else collectCursor(cursor.right, foundCursors :+ cursor)
      }

      collectCursor(current, Vector.empty)
    }
  }
}

sealed class SyncDecoder[A](val decoder: Decoder[A]) extends ADecoder[Id, A] {

  override type DECODER[T] = SyncDecoder[T]
  override type CREATOR = SyncDecoderCreator.type

  override val creator: SyncDecoderCreator.type = SyncDecoderCreator

  override def apply(c: HCursor): Id[Either[DecodingFailure, A]] = decoder.apply(c)

  override def tryDecode(c: ACursor): Id[Either[DecodingFailure, A]] = decoder.tryDecode(c)

  override def map[B](f: A => B): SyncDecoder[B] = new SyncDecoder(decoder.map(f))

  override def emap[B](f: A => Either[String, B]): SyncDecoder[B] = new SyncDecoder(decoder.emap(f))

  override def flatMap[B](f: A => SyncDecoder[B]): SyncDecoder[B] = new SyncDecoder(decoder.flatMap(a => f(a).decoder))
}

object SyncDecoderCreator extends ADecoderCreator[Id, SyncDecoder] {

  def from[A](decoder: ADecoder[Id, A]): SyncDecoder[A] = decoder.asInstanceOf[SyncDecoder[A]]

  override def instance[A](f: HCursor => Id[Either[DecodingFailure, A]]): SyncDecoder[A] =
    new SyncDecoder(Decoder.instance(f))

  override def from[A](decoder: Decoder[A]): SyncDecoder[A] =
    new SyncDecoder(decoder)

  override def list[A](implicit decoder: ADecoder[Id, A]): SyncDecoder[List[A]] =
    new SyncDecoder(Decoder.decodeList[A](from(decoder).decoder))
}
