package tech.beshu.ror.acl.blocks.values

import com.jayway.jsonpath.JsonPath
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.acl.blocks.values.RuntimeValueParser.ParserState._
import tech.beshu.ror.acl.blocks.values.RuntimeValueParser.Parts._
import tech.beshu.ror.acl.blocks.values.RuntimeValueParser.{ParserState, Parts, regexes}
import tech.beshu.ror.acl.blocks.values.Variable.ConvertError
import tech.beshu.ror.acl.domain.Header

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

class RuntimeValueParser {

  def parse[T](text: String,
               convert: String => Either[ConvertError, T]): Variable[T] = {
    val values = splitIntoParts(text)
      .map {
        case Text(value) => Const(value)
        case Placeholder(value) =>
          value match {
            case regexes.userVar =>
              UserIdVar
            case regexes.jwtPayloadPathVar(path) =>
              Try(JsonPath.compile(path)) match {
                case Success(compiledPath) => JwtPayloadVar(compiledPath)
                case Failure(ex) => throw ex // todo:
              }
            case regexes.explicitHeaderVar(headerName) =>
              NonEmptyString.unapply(headerName) match {
                case Some(nes) => HeaderVar(Header.Name(nes))
                case None => throw Exception // todo:
              }
            case other => // backward compatibility - assuming that it's header
              NonEmptyString.unapply(other) match {
                case Some(nes) => HeaderVar(Header.Name(nes))
                case None => throw Exception // todo:
              }
          }
      }
    values.toList match {
      case Nil => throw Exception // todo:
      case nonEmpty => Variable(nonEmpty, convert)
    }
  }

  private def splitIntoParts(text: String) = {
    val init: (Vector[Parts], ParserState) = (Vector.empty[Parts], ReadingConst(""))
    val (lastParts, lastState) = text.foldLeft(init) {
      case ((parts, ReadingConst(accumulator)), char) =>
        char match {
          case '@' =>
            (parts, PossiblyReadingVar(accumulator))
          case other =>
            (parts, ReadingConst(accumulator + other))
        }
      case ((parts, PossiblyReadingVar(accumulator)), char) =>
        char match {
          case '{' =>
            val newParts = if (accumulator.nonEmpty) parts :+ Text(accumulator) else parts
            (newParts :+ Text(accumulator), ReadingVar(""))
          case other =>
            (parts, ReadingConst(s"$accumulator@$other"))
        }
      case ((parts, ReadingVar(accumulator)), char) =>
        char match {
          case '}' =>
            (parts :+ Placeholder(accumulator), ReadingConst(""))
          case other =>
            (parts, ReadingVar(accumulator + other))
        }
    }
    lastParts :+ (lastState match {
      case ReadingConst(accumulator) => Text(accumulator)
      case PossiblyReadingVar(constAccumulator) => Text(constAccumulator + '@')
      case ReadingVar(accumulator) => Text(accumulator)
    })
  }
}

private object RuntimeValueParser {

  sealed trait ParserState
  object ParserState {
    final case class ReadingConst(accumulator: String) extends ParserState
    final case class PossiblyReadingVar(constAccumulator: String) extends ParserState
    final case class ReadingVar(accumulator: String) extends ParserState
  }

  sealed trait Parts
  object Parts {
    final case class Text(value: String) extends Parts
    final case class Placeholder(value: String) extends Parts
  }

  object regexes {
    val userVar: Regex = "user".r
    val explicitHeaderVar: Regex = "header:(.*)".r
    val jwtPayloadPathVar: Regex = "jwt:(.*)".r
  }

}