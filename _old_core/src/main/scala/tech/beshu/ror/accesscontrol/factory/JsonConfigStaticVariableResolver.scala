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
package tech.beshu.ror.accesscontrol.factory

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import cats.implicits._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariableCreator.{createMultiVariableFrom, createSingleVariableFrom}
import tech.beshu.ror.providers.EnvVarsProvider

object JsonConfigStaticVariableResolver {

  def resolve(json: Json)
             (implicit envProvider: EnvVarsProvider): Either[NonEmptyList[ResolvingError], Json] = {
    val errors = ResolvingErrors(Vector.empty)
    val jsonWithResolvedVars = mapJson(json, errors)
    errors.values.toList match {
      case Nil => Right(jsonWithResolvedVars)
      case resolvingErrors => Left(NonEmptyList.fromListUnsafe(resolvingErrors))
    }
  }

  private def mapJson(json: Json, errors: ResolvingErrors)
                     (implicit envProvider: EnvVarsProvider): Json = {
    json
      .mapArray(_.flatMap { json =>
        json.asString.flatMap(NonEmptyString.unapply) match {
          case Some(str) =>
            tryToResolveAllStaticMultipleVars(str, errors)
              .map(resolvedStringToJson)
              .toList
          case None =>
            mapJson(json, errors) :: Nil
        }
      })
      .mapBoolean(identity)
      .mapNumber(identity)
      .mapObject(_.mapValues(mapJson(_, errors)))
      .withString { str =>
        val resolved = NonEmptyString.unapply(str) match {
          case Some(nes) => tryToResolveAllStaticSingleVars(nes, errors)
          case None => str
        }
        resolvedStringToJson(resolved)
      }
  }

  private def resolvedStringToJson(resolvedStr: String) = {
    def isJsonPrimitive(json: Json) = !(json.isObject || json.isArray)
    io.circe.parser.parse(resolvedStr) match {
      case Right(newJsonValue) if isJsonPrimitive(newJsonValue) => newJsonValue
      case Right(_) => Json.fromString(resolvedStr)
      case Left(_) => Json.fromString(resolvedStr)
    }
  }

  private def tryToResolveAllStaticSingleVars(str: NonEmptyString, errors: ResolvingErrors)
                                             (implicit envProvider: EnvVarsProvider): String = {
    createSingleVariableFrom(str) match {
      case Right(variable) =>
        variable.resolve(envProvider) match {
          case Right(extracted) => extracted
          case Left(error) =>
            errors.values = errors.values :+ ResolvingError(error.msg)
            str.value
        }
      case Left(error) =>
        errors.values = errors.values :+ ResolvingError(error.show)
        str.value
    }
  }

  private def tryToResolveAllStaticMultipleVars(str: NonEmptyString, errors: ResolvingErrors)
                                               (implicit envProvider: EnvVarsProvider): NonEmptyList[String] = {
    createMultiVariableFrom(str) match {
      case Right(variable) =>
        variable.resolve(envProvider) match {
          case Right(extracted) => extracted
          case Left(error) =>
            errors.values = errors.values :+ ResolvingError(error.msg)
            NonEmptyList.one(str.value)
        }
      case Left(error) =>
        errors.values = errors.values :+ ResolvingError(error.show)
        NonEmptyList.one(str.value)
    }
  }

  final case class ResolvingError(msg: String) extends AnyVal

  private final case class ResolvingErrors(var values: Vector[ResolvingError])

}
