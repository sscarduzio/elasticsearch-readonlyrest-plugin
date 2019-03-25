package tech.beshu.ror.acl.blocks.values

import cats.instances.list._
import cats.instances.either._
import cats.syntax.traverse._
import com.jayway.jsonpath.JsonPath
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.acl.blocks.values.Variable.ConvertError
import tech.beshu.ror.acl.domain.Header

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object VariableParser {

  final case class ParseError(msg: String, ex: Option[Throwable] = None)

  def parse[T](text: String,
               convert: String => Either[ConvertError, T]): Either[ParseError, Variable[T]] = {
    splitIntoParts(text)
      .map {
        case Parts.Text(value) =>
          Right(Const(value))
        case Parts.Placeholder(value) =>
          value match {
            case regexes.userVar() =>
              Right(UserIdVar)
            case regexes.jwtPayloadPathVar(path) =>
              Try(JsonPath.compile(path)) match {
                case Success(compiledPath) => Right(JwtPayloadVar(compiledPath))
                case Failure(ex) => Left(ParseError(s"Cannot compile '$path' to JsonPath", Some(ex)))
              }
            case regexes.explicitHeaderVar(headerName) =>
              NonEmptyString.unapply(headerName) match {
                case Some(nes) => Right(HeaderVar(Header.Name(nes)))
                case None => Left(ParseError(s"No header name passed"))
              }
            case other => // backward compatibility - assuming that it's header
              NonEmptyString.unapply(other) match {
                case Some(nes) => Right(HeaderVar(Header.Name(nes)))
                case None => Left(ParseError(s"No header name passed"))
              }
          }
      }
      .toList
      .sequence
      .flatMap {
        case Nil => Left(ParseError(s"Cannot create variable from '$text'"))
        case nonEmpty =>
          val alreadyResolved = nonEmpty.collect { case c: Const => c }
          if(alreadyResolved.length == nonEmpty.length) {
            convert(alreadyResolved.map(_.value).foldLeft("")(_ + _))
              .map(AlreadyResolved.apply)
              .left.map(e => ParseError(e.msg))
          } else {
            Right(ToBeResolved[T](nonEmpty, convert))
          }
      }
  }

  private def splitIntoParts(text: String) = {
    val init: (Vector[Parts], ParserState) = (Vector.empty[Parts], ParserState.ReadingConst(""))
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
    })
  }

  private sealed trait ParserState
  private object ParserState {
    final case class ReadingConst(accumulator: String) extends ParserState
    final case class PossiblyReadingVar(constAccumulator: String) extends ParserState
    final case class ReadingVar(accumulator: String) extends ParserState
  }

  private sealed trait Parts
  private object Parts {
    final case class Text(value: String) extends Parts
    final case class Placeholder(value: String) extends Parts
  }

  private object regexes {
    val userVar: Regex = "user".r
    val explicitHeaderVar: Regex = "header:(.*)".r
    val jwtPayloadPathVar: Regex = "jwt:(.*)".r
  }

}