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

import cats.Show
import cats.data.NonEmptyList
import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.ExpressionCompiler._
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.FunctionDefinition.FunctionArg
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.{Function, FunctionAlias, FunctionDefinition, FunctionName}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.parser.Parser.Expression

private[transformation] class ExpressionCompiler private(functions: Seq[FunctionDefinition],
                                                         aliasedFunctions: Seq[FunctionAlias]) {

  import ExpressionCompiler.CompilationResult._

  type Result[A] = Either[CompilationError, A]

  def compile(expression: Expression, useAlias: Boolean): Result[Function] = {
    compileExpression(expression)
      .flatMap {
        case Chain(chain) =>
          chain
            .map(findFunction(_, useAlias))
            .sequence
            .map(new Function.FunctionChain(_))
        case c: Call =>
          findFunction(c, useAlias)
      }
  }

  private def compileExpression(expression: Expression): Result[CompilationResult] = expression match {
    case Expression.Name(value) =>
      toFunctionName(value)
        .map { name =>
          Call(name, List.empty)
        }
    case Expression.Call(name, args) =>
      for {
        funName <- asFunctionName(name)
        funArgs <- getArgs(args)
      } yield Call(funName, funArgs)
    case Expression.Chain(left, right) =>
      for {
        leftResult <- compileExpression(left)
        rightResult <- compileExpression(right)
      } yield combine(leftResult, rightResult)
  }

  private def asFunctionName(expression: Expression): Result[FunctionName] = {
    expression match {
      case Expression.Name(value) => toFunctionName(value)
      case _: Expression.Call => Left(toError("Expected function name but was function call"))
      case _: Expression.Chain => Left(toError("Expected function name but was function call chain"))
    }
  }

  private def toFunctionName(value: String): Result[FunctionName] = {
    NonEmptyString
      .unapply(value)
      .map(FunctionName.apply)
      .toRight(toError("Function name cannot be empty"))
  }

  private def getArgs(args: List[Expression]): Result[List[FunctionArg]] = {
    args
      .map {
        case Expression.Name(value) => Right(FunctionArg(value))
        case _: Expression.Call => Left(toError("Functions nesting is not supported"))
        case _: Expression.Chain => Left(toError("Functions nesting is not supported"))
      }
      .sequence
  }

  private def combine(left: CompilationResult, right: CompilationResult): CompilationResult = (left, right) match {
    case (l: Call, r: Call) => Chain(NonEmptyList.of(l, r))
    case (l: Chain, r: Call) => Chain(l.value.append(r))
    case (l: Call, r: Chain) => Chain(r.value.prepend(l))
    case (l: Chain, r: Chain) => Chain(l.value.concatNel(r.value))
  }

  private def findFunction(call: Call, useAlias: Boolean): Result[Function] = {
    call.name match {
      case FunctionName(Refined("func")) =>
        for {
          _ <- Either.cond(useAlias, (), toError("Function aliases cannot be applied in this context"))
          firstArg <- call.args match {
            case arg :: Nil => Right(arg)
            case other => Left(toError(s"One argument is required, but was ${other.size}"))
          }
          aliasName <-
            NonEmptyString
              .unapply(firstArg.value)
              .map(FunctionName.apply)
              .toRight(toError("The function alias name cannot be empty"))
          alias <- findAlias(aliasName)
        } yield alias.value
      case functionName =>
        functions
          .find(f => f.functionName == functionName)
          .toRight(toError(s"No function with name '${call.name.name.value}'. Supported functions are: [${functions.map(_.show).mkString(",")}]"))
          .flatMap {
            _.partiallyApplied(call.args).left.map { partialApplyError =>
              toError(s"Error for function '${functionName.name.value}': ${partialApplyError.message}")
            }
          }
    }
  }

  private def findAlias(name: FunctionName): Result[FunctionAlias] = {
    aliasedFunctions
      .find(_.name == name)
      .toRight(toError(s"Alias with name '${name.name.value}' does not exits."))
  }

  private def toError(message: String) = CompilationError(message)
}

private[transformation] object ExpressionCompiler {

  def create(functions: Seq[FunctionDefinition],
             aliases: Seq[FunctionAlias]): ExpressionCompiler = {
    new ExpressionCompiler(functions, aliases)
  }

  final case class CompilationError(message: String)
  sealed trait CompilationResult
  object CompilationResult {
    final case class Chain(value: NonEmptyList[Call]) extends CompilationResult
    final case class Call(name: FunctionName, args: List[FunctionArg]) extends CompilationResult
  }

  implicit val showCompilationResultCall: Show[CompilationResult.Call] = { r =>
    val args: List[String] = r.args.map(_.value).map((_: String) => "string")
    val name = r.name.name.value
    s"$name(${args.mkString(",")})"
  }

  implicit val showCompilationResultChain: Show[CompilationResult.Chain] = Show.show { r =>
    r.value.map(_.show).mkString_(".")
  }

  implicit val showFunctionDefinition: Show[FunctionDefinition] = Show.show(_.functionName.name.value)

}
