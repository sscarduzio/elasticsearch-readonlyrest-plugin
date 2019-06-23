package tech.beshu.ror.acl.factory

import cats.data.NonEmptyList
import io.circe.Json
import tech.beshu.ror.acl.blocks.variables.startup.StartupResolvableVariableCreator
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
      .mapArray(_.map(mapJson(_, errors)))
      .mapBoolean(identity)
      .mapNumber(identity)
      .mapObject(_.mapValues(mapJson(_, errors)))
      .mapString(tryToResolveAllVars(_, errors))
  }

  private def tryToResolveAllVars(value: String, errors: ResolvingErrors)
                                 (implicit envProvider: EnvVarsProvider): String = {
    StartupResolvableVariableCreator.createFrom(value) match {
      case Right(variable) =>
        variable.resolve(envProvider) match {
          case Right(extracted) => extracted
          case Left(error) =>
            errors.values = errors.values :+ ResolvingError(error.msg)
            value
        }
      case Left(error) =>
        errors.values = errors.values :+ ResolvingError(error.msg)
        value
    }
  }

  final case class ResolvingError(msg: String) extends AnyVal

  private final case class ResolvingErrors(var values: Vector[ResolvingError])
}
