package tech.beshu.ror.acl.blocks.variables.startup

import cats.data.NonEmptyList
import cats.instances.either._
import cats.syntax.show._
import cats.syntax.traverse._
import com.github.tototoshi.csv.{CSVParser, _}
import tech.beshu.ror.acl.blocks.variables.startup.StartupResolvableVariable.ResolvingError
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.utils.ScalaOps._

sealed trait StartupMultiResolvableVariable extends StartupResolvableVariable[NonEmptyList[String]]
object StartupMultiResolvableVariable {

  final case class Env(name: EnvVarName) extends StartupMultiResolvableVariable {
    private val csvParser =  new CSVParser(new DefaultCSVFormat {})
    override def resolve(provider: EnvVarsProvider): Either[ResolvingError, NonEmptyList[String]] = {
      provider.getEnv(name) match {
        case Some(envValue) =>
          (for {
            values <- csvParser.parseLine(envValue)
            result <- NonEmptyList.fromList(values)
          } yield result) match {
            case Some(value) => Right(value)
            case None => Right(NonEmptyList.one(""))
          }
        case None =>
          Left(ResolvingError(s"Cannot resolve ENV variable '${name.show}'"))
      }
    }
  }

  final case class Text(value: String) extends StartupMultiResolvableVariable {
    private val singleText = StartupSingleResolvableVariable.Text(value)
    override def resolve(provider: EnvVarsProvider): Either[ResolvingError, NonEmptyList[String]] =
      singleText.resolve(provider).map(NonEmptyList.one)
  }

  final case class Composed(vars: NonEmptyList[StartupResolvableVariable[NonEmptyList[String]]]) extends StartupMultiResolvableVariable {
    override def resolve(provider: EnvVarsProvider): Either[ResolvingError, NonEmptyList[String]] = {
      vars
        .map(_.resolve(provider))
        .sequence
        .map { resolvedVars =>
          resolvedVars.cartesian.map(_.toList.mkString)
        }
      }
  }

}
