package tech.beshu.ror.acl.factory

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import cats.implicits._
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.blocks.variables.startup.StartupResolvableVariableCreator.{createMultiVariableFrom, createSingleVariableFrom}
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
            tryToResolveAllStaticMultipleVars(str, errors).map(Json.fromString).toList
          case None =>
            mapJson(json, errors) :: Nil
        }
      })
      .mapBoolean(identity)
      .mapNumber(identity)
      .mapObject(_.mapValues(mapJson(_, errors)))
      .mapString { str =>
        NonEmptyString.unapply(str) match {
          case Some(nes) => tryToResolveAllStaticSingleVars(nes, errors)
          case None => str
        }
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
