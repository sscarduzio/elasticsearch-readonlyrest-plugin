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
package tech.beshu.ror.accesscontrol.blocks.variables.transformation

import cats.data.NonEmptyList
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.Utils._
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.Function._
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.FunctionDefinition.{FunctionArg, PartialApplyError}

import java.util.Locale
import java.util.regex.PatternSyntaxException
import scala.util.Try
import scala.util.matching.Regex

object domain {

  final case class FunctionName(name: NonEmptyString) // todo improvement refined type with regex matching function name

  sealed trait FunctionDefinition {
    def functionName: FunctionName
    def argsCount: Int
    def partiallyApplied: List[FunctionArg] => Either[PartialApplyError, Function]
  }

  object FunctionDefinition {
    def apply[F <: Function : FunctionPartialApply](name: NonEmptyString): FunctionDefinition = {
      new FunctionDefinition {
        override def functionName: FunctionName = FunctionName(name)

        override def argsCount: Int = implicitly[FunctionPartialApply[F]].argsCount

        override def partiallyApplied: List[FunctionArg] => Either[PartialApplyError, Function] =
          implicitly[FunctionPartialApply[F]].partialApply
      }
    }

    final case class FunctionArg(value: String)
    final case class PartialApplyError(message: String)
  }

  final case class FunctionAlias(name: FunctionName, value: Function)

  // todo improvement Function[A,B]
  sealed trait Function {
    def apply(value: String): String
  }

  object Function {
    sealed trait NoArgsFunction extends Function

    final class FunctionChain(functions: NonEmptyList[Function]) extends Function {
      override def apply(value: String): String = {
        functions.tail.foldLeft(functions.head.apply(value)) { (result, f) =>
          f.apply(result)
        }
      }
    }

    final class ReplaceAll(regex: Regex, replacement: String) extends Function {
      def apply(value: String): String = value.replaceAll(regex.regex, replacement)
    }

    final class ReplaceFirst(regex: Regex, replacement: String) extends Function {
      def apply(value: String): String = {
        value.replaceFirst(regex.regex, replacement)
      }
    }

    final class ToUpperCase extends NoArgsFunction {
      def apply(value: String): String = value.toUpperCase(Locale.US)
    }

    final class ToLowerCase extends NoArgsFunction {
      def apply(value: String): String = value.toLowerCase(Locale.US)
    }
  }

  sealed trait FunctionPartialApply[F <: Function] {
    def argsCount: Int
    def partialApply(args: List[FunctionArg]): Either[PartialApplyError, F]
  }

  object FunctionPartialApply {
    implicit val replaceAllPartialApply: FunctionPartialApply[ReplaceAll] = new FunctionPartialApply[ReplaceAll] {
      override def argsCount: Int = 2

      override def partialApply(args: List[FunctionArg]): Either[PartialApplyError, ReplaceAll] = args match {
        case pattern :: replacement :: Nil =>
          Try(pattern.value.r).toEither
            .leftMap { ex =>
              PartialApplyError(s"Incorrect first arg '${pattern.value}'. Cause ${patternErrorFor(ex)}")
            }
            .map { regex =>
              new Function.ReplaceAll(regex, replacement.value)
            }
        case other =>
          Left(incorrectArgsCountError(argsCount, other))
      }
    }

    implicit val replaceFirstPartialApply: FunctionPartialApply[ReplaceFirst] = new FunctionPartialApply[ReplaceFirst] {
      override def argsCount: Int = 2

      override def partialApply(args: List[FunctionArg]): Either[PartialApplyError, ReplaceFirst] = args match {
        case pattern :: replacement :: Nil =>
          Try(pattern.value.r).toEither
            .leftMap { ex =>
              PartialApplyError(s"Incorrect first arg '${pattern.value}'. Cause ${patternErrorFor(ex)}")
            }
            .map { regex =>
              new Function.ReplaceFirst(regex, replacement.value)
            }
        case other =>
          Left(incorrectArgsCountError(argsCount, other))
      }
    }

    implicit val toUppercasePartialApply: FunctionPartialApply[ToUpperCase] = noArgsPartialApply(new ToUpperCase)
    implicit val toLowercasePartialApply: FunctionPartialApply[ToLowerCase] = noArgsPartialApply(new ToLowerCase)

    private def noArgsPartialApply[F <: NoArgsFunction](f: F): FunctionPartialApply[F] = new FunctionPartialApply[F] {
      override def argsCount: Int = 0

      override def partialApply(args: List[FunctionArg]): Either[PartialApplyError, F] =
        if (args.isEmpty) {
          Right(f)
        } else {
          Left(incorrectArgsCountError(argsCount, args))
        }
    }

    private def incorrectArgsCountError(expected: Int, args: List[FunctionArg]) = {
      PartialApplyError(s"Incorrect function arguments count. Expected: $expected, actual: ${args.size}.")
    }

    private def patternErrorFor(ex: Throwable): String = ex match {
      case ex: PatternSyntaxException => ex.getPrettyMessage
      case other => other.getMessage
    }
  }

}
