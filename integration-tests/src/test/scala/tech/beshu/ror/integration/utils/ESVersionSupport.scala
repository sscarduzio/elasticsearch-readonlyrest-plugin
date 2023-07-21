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
import org.scalatest.Tag
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.wordspec.AnyWordSpecLike
import tech.beshu.ror.utils.gradle.RorPluginGradleProject
import tech.beshu.ror.utils.misc.{EsModule, EsModulePatterns}

import scala.util.matching.Regex

trait ESVersionSupportForAnyWordSpecLike extends ESVersionSupport {
  this: AnyWordSpecLike =>

  override type T = ResultOfTaggedAsInvocationOnString

  override def stringTaggedAs(string: String, firstTestTag: Tag, otherTestTags: Tag*): T =
    string.taggedAs(firstTestTag, otherTestTags: _*)
}

trait ESVersionSupportForAnyFreeSpecLike extends ESVersionSupport {
  this: AnyFreeSpecLike =>

  override type T = ResultOfTaggedAsInvocationOnString

  override def stringTaggedAs(string: String, firstTestTag: Tag, otherTestTags: Tag*): T =
    string.taggedAs(firstTestTag, otherTestTags: _*)
}

sealed trait ESVersionSupport extends EsModulePatterns {

  type T

  protected def stringTaggedAs(string: String, firstTestTag: Tag, otherTestTags: Tag*): T

  implicit final class ESVersionSupportOps(string: String) {
    def excludeES(esVersion: String, esVersions: String*): T = {
      stringTaggedAs(string, new ExcludeESModule(esVersion), esVersions.map(new ExcludeESModule(_)).toList: _*)
    }

    def excludeES(regex: Regex, regexArgs: Regex*): T = {
      val excludedModuleNames = EsModule.getExcludedModuleNames(regex, regexArgs: _*)
      NonEmptyList.fromList(excludedModuleNames) match {
        case Some(esModules) =>
          val excludedEsModules = esModules.map(module => new ExcludeESModule(module.name))
          stringTaggedAs(string, excludedEsModules.head, excludedEsModules.tail: _*)
        case None =>
          throw new IllegalStateException("None of ES module was excluded")
      }
    }

  }

  def executedOn(regex: Regex, regexArgs: Regex*): Boolean = {
    (regex :: regexArgs.toList).exists(_.findFirstIn(RorPluginGradleProject.fromSystemProperty.moduleName).isDefined)
  }

  private final class ExcludeESModule(value: String) extends Tag(s"tech.beshu.tags.ExcludeESModule.$value")

  lazy val esVersionUsed: String = RorPluginGradleProject.fromSystemProperty.getESVersion
}