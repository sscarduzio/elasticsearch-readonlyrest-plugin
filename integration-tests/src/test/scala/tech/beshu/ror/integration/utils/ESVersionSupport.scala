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
package tech.beshu.ror.integration.utils

import cats.data.NonEmptyList
import org.scalatest.{Tag, WordSpecLike}
import tech.beshu.ror.utils.gradle.RorPluginGradleProject

import scala.language.implicitConversions
import scala.util.matching.Regex

trait ESVersionSupport extends WordSpecLike {

  val allEs5x = "^es5\\dx$".r
  val allEs6x = "^es6\\dx$".r
  val allEs6xBelowEs63x = "^es6[0-2]x$".r
  val allEs6xExceptEs66x = "^es6(?!(?:6x)$)\\dx$".r
  val allEs7x = "^es7\\dx$".r
  val allEs7xExceptEs70x = "^es7(?!(?:0x)$)\\dx$".r
  val allEs7xBelowEs77x = "^es7[0-6]x$".r

  implicit final class ESVersionSupportOps(string: String) {
    def excludeES(esVersion: String, esVersions: String*): ResultOfTaggedAsInvocationOnString = {
      string.taggedAs(new ExcludeESModule(esVersion), esVersions.map(new ExcludeESModule(_)).toList: _*)
    }

    def excludeES(regex: Regex, regexArgs: Regex*): ResultOfTaggedAsInvocationOnString = {
      val excludedModuleNames = RorPluginGradleProject
        .availableEsModules
        .filter { name =>
          (regex :: regexArgs.toList).exists(_.findFirstIn(name).isDefined)
        }
      NonEmptyList.fromList(excludedModuleNames) match {
        case Some(names) =>
          val excludedEsModules = names.map(new ExcludeESModule(_))
          string.taggedAs(excludedEsModules.head, excludedEsModules.tail: _*)
        case None =>
          throw new IllegalStateException("None of ES module was excluded")
      }
    }
  }

  private final class ExcludeESModule(value: String) extends Tag(s"tech.beshu.tags.ExcludeESModule.$value")

}
