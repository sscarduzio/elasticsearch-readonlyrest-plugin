package tech.beshu.ror.acl.blocks.values

import cats.instances.list._
import cats.instances.either._
import cats.syntax.traverse._
import com.jayway.jsonpath.JsonPath
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.acl.blocks.values.Variable.ConvertError
import tech.beshu.ror.acl.domain.{EnvVarName, Header}
import tech.beshu.ror.acl.utils.EnvVarsProvider

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object VariableCreator {

  final case class CreationError(msg: String, ex: Option[Throwable] = None)

  def createFrom[T](text: String,
                    convert: String => Either[ConvertError, T])
                   (implicit provider: EnvVarsProvider): Either[CreationError, Variable[T]] = {
    oldFashionedEnvAndTextHandling(text).map(_ :: Nil)
      .getOrElse(splitIntoParts(text).map(createValue))
      .map(_.flatMap(resolveStaticValues))
      .sequence
      .flatMap {
        case Nil => Left(CreationError(s"Cannot create variable from '$text'"))
        case nonEmpty =>
          val alreadyResolved = nonEmpty.collect { case c: Const => c }
          if(alreadyResolved.length == nonEmpty.length) {
            convert(alreadyResolved.map(_.value).foldLeft("")(_ + _))
              .map(AlreadyResolved.apply)
              .left.map(e => CreationError(e.msg))
          } else {
            Right(ToBeResolved[T](nonEmpty, convert))
          }
      }
  }

  private def splitIntoParts(text: String) = {
    val init: (Vector[Part], ParserState) = (Vector.empty[Part], ParserState.ReadingConst(""))
    val (lastParts, lastState) = text.foldLeft(init) {
      case ((parts, ParserState.ReadingConst(accumulator)), char) =>
        char match {
          case '@' =>
            (parts, ParserState.PossiblyReadingVar(accumulator))
          case other =>
            (parts, ParserState.ReadingConst(accumulator + other))
        }
      case ((parts, ParserState.PossiblyReadingVar(accumulator)), char) =>
        char match {
          case '{' =>
            val newParts = if (accumulator.nonEmpty) parts :+ Parts.Text(accumulator) else parts
            (newParts, ParserState.ReadingVar(""))
          case  '@' =>
            (parts, ParserState.PossiblyReadingVar(accumulator + "@"))
          case other =>
            (parts, ParserState.ReadingConst(s"$accumulator@$other"))
        }
      case ((parts, ParserState.ReadingVar(accumulator)), char) =>
        char match {
          case '}' =>
            (parts :+ Parts.Placeholder(accumulator), ParserState.ReadingConst(""))
          case other =>
            (parts, ParserState.ReadingVar(accumulator + other))
        }
    }
    lastParts ++ (lastState match {
      case ParserState.ReadingConst("") => Nil
      case ParserState.ReadingConst(accumulator) => Parts.Text(accumulator) :: Nil
      case ParserState.PossiblyReadingVar(constAccumulator) => Parts.Text(constAccumulator + "@") :: Nil
      case ParserState.ReadingVar(accumulator) => Parts.Text("@{" + accumulator) :: Nil
    }) toList
  }

  private def createValue(part: Part) = part match {
    case Parts.Text(value) =>
      Right(Const(value))
    case Parts.Placeholder(value) =>
      value match {
        case regexes.userVar() =>
          Right(UserIdVar)
        case regexes.envVar(envVarName) =>
          NonEmptyString.unapply(envVarName) match {
            case Some(nes) => Right(EnvVar(EnvVarName(nes)))
            case None => Left(CreationError(s"No ENV variable name passed"))
          }
        case regexes.jwtPayloadPathVar(path) =>
          Try(JsonPath.compile(path)) match {
            case Success(compiledPath) => Right(JwtPayloadVar(compiledPath))
            case Failure(ex) => Left(CreationError(s"Cannot compile '$path' to JsonPath", Some(ex)))
          }
        case regexes.explicitHeaderVar(headerName) =>
          NonEmptyString.unapply(headerName) match {
            case Some(nes) => Right(HeaderVar(Header.Name(nes)))
            case None => Left(CreationError(s"No header name passed"))
          }
        case other => // backward compatibility - assuming that it's header
          NonEmptyString.unapply(other) match {
            case Some(nes) => Right(HeaderVar(Header.Name(nes)))
            case None => Left(CreationError(s"No header name passed"))
          }
      }
  }

  private def resolveStaticValues(value: Value)
                                 (implicit provider: EnvVarsProvider)=
    value match {
      case value: StartupValue =>
        value
          .extract(provider)
          .map(Const.apply)
          .left.map(e => CreationError(e.msg))
      case value: RuntimeValue =>
        Right(value)
    }

  private def oldFashionedEnvAndTextHandling(text: String): Option[Either[CreationError, Value]] = {
    if (text.startsWith("env:")) {
      NonEmptyString.unapply(text.substring("env:".length)).map(str => EnvVar(EnvVarName(str))).map(Right.apply)
    } else if(text.startsWith("text:")) {
      Some(Right(Text(text.substring("text:".length))))
    } else {
      None
    }
  }

  private sealed trait ParserState
  private object ParserState {
    final case class ReadingConst(accumulator: String) extends ParserState
    final case class PossiblyReadingVar(constAccumulator: String) extends ParserState
    final case class ReadingVar(accumulator: String) extends ParserState
  }

  private sealed trait Part
  private object Parts {
    final case class Text(value: String) extends Part
    final case class Placeholder(value: String) extends Part
  }

  private object regexes {
    val userVar: Regex = "user".r
    val explicitHeaderVar: Regex = "header:(.*)".r
    val jwtPayloadPathVar: Regex = "jwt:(.*)".r
    val envVar: Regex = "env:(.*)".r
  }

}