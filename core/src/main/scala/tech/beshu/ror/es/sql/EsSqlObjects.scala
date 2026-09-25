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
package tech.beshu.ror.es.sql

import org.joor.Reflect.on
import tech.beshu.ror.es.sql.CommandSelector.{
  AppendableIndexList,
  CannotNarrow,
  LiteralIndexList,
  MatchingPattern,
  NotIndexRelated
}
import tech.beshu.ror.es.sql.SqlQueryIndicesReader.{SourceLocation, TableInQuery}

import scala.util.Try

private[sql] object EsSqlObjects {

  private val commandsReadingIndices = Set("ShowTables", "ShowColumns", "SysTables", "SysColumns")

  private val commandsTakingAnIndexList = Set("ShowTables", "ShowColumns", "SysTables")

  private val commandsTakingNoSelector = Set("ShowTables", "SysTables")

  def tableInQuery(tableIdentifier: Any): Option[TableInQuery] =
    Try {
      val source = on(tableIdentifier).call("source").get[Any]()
      val location = on(source).call("source").get[Any]()
      TableInQuery(
        // not index(), which drops the remote cluster a query may write as `cluster:index`
        reportedIndexList = on(tableIdentifier).call("qualifiedIndex").get[String](),
        writtenAt = SourceLocation(
          line = on(location).call("getLineNumber").get[Int](),
          column = on(location).call("getColumnNumber").get[Int]() - 1
        ),
        writtenText = on(source).call("text").get[String]()
      )
    }.toOption

  def selectorOf(command: Any): CommandSelector = {
    val commandName = command.getClass.getSimpleName
    if (!commandsReadingIndices.contains(commandName)) NotIndexRelated
    else if (!commandsTakingAnIndexList.contains(commandName)) CannotNarrow(commandName)
    else
      literalIndexListOf(command)
        .orElse(matchingPatternOf(command, commandName))
        .getOrElse(
          if (commandsTakingNoSelector.contains(commandName)) AppendableIndexList
          else CannotNarrow(commandName)
        )
  }

  private def literalIndexListOf(command: Any): Option[LiteralIndexList] =
    fieldOf[String](command, "index").map(LiteralIndexList.apply)

  private def matchingPatternOf(command: Any, commandName: String): Option[MatchingPattern] =
    for {
      pattern <- fieldOf[AnyRef](command, "pattern")
      wildcard <- Try(on(pattern).call("asIndexNameWildcard").get[String]()).toOption
    } yield MatchingPattern(wildcard, commandName)

  private def fieldOf[T](underlyingObject: Any, name: String): Option[T] =
    Try(Option(on(underlyingObject).get[T](name))).toOption.flatten

}
