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
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.{Tag, TestSuite}
import org.scalatest.wordspec.AnyWordSpecLike
import tech.beshu.ror.utils.gradle.RorPluginGradleProject

import scala.language.implicitConversions
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

sealed trait ESVersionSupport {
  this: TestSuite =>

  type T

  val es60x = "^es60x$".r
  val allEs6x = "^es6\\dx$".r
  val allEs6xBelowEs63x = "^es6[0-2]x$".r
  val allEs6xBelowEs65x = "^es6[0-4]x$".r
  val allEs6xBelowEs66x = "^es6[0-5]x$".r
  val allEs6xExceptEs67x = "^es6(?!(?:7x)$)\\dx$".r
  val allEs7x = "^es7\\d+x$".r
  val allEs7xExceptEs70x = "^es(?!(?:70x)$)7\\d+x$".r
  val allEs7xBelowEs74x = "^es7[0-3]x$".r
  val allEs7xBelowEs77x = "^es7[0-6]x$".r
  val allEs7xBelowEs78x = "^es7[0-7]x$".r
  val allEs7xBelowEs79x = "^es7[0-8]x$".r
  val allEs7xBelowEs716x = "^es7(0?[0-9]|1[0-5])x$".r
  val allEs7xBelowEs714x = "^es7(0?[0-9]|1[0-3])x$".r
  val allEs7xBelowEs711x = "^es7(0?[0-9]|10)x$".r
  val allEs8x = "^es8\\d+x$".r
  val allEs8xBelowEs87x = "^es8[0-6]x$".r
  val rorProxy = "^proxy$".r

  protected def stringTaggedAs(string: String, firstTestTag: Tag, otherTestTags: Tag*): T

  implicit final class ESVersionSupportOps(string: String) {
    def excludeES(esVersion: String, esVersions: String*): T = {
      stringTaggedAs(string, new ExcludeESModule(esVersion), esVersions.map(new ExcludeESModule(_)).toList: _*)
    }

    def excludeES(regex: Regex, regexArgs: Regex*): T = {
      val excludedModuleNames = ("proxy" :: RorPluginGradleProject.availableEsModules)
        .filter { name =>
          (regex :: regexArgs.toList).exists(_.findFirstIn(name).isDefined)
        }
      NonEmptyList.fromList(excludedModuleNames) match {
        case Some(names) =>
          val excludedEsModules = names.map(new ExcludeESModule(_))
          stringTaggedAs(string, excludedEsModules.head, excludedEsModules.tail: _*)
        case None =>
          throw new IllegalStateException("None of ES module was excluded")
      }
    }

  }

  def executedOn(regex: Regex, regexArgs: Regex*) = {
    (regex :: regexArgs.toList).exists(_.findFirstIn(RorPluginGradleProject.fromSystemProperty.moduleName).isDefined)
  }

  private final class ExcludeESModule(value: String) extends Tag(s"tech.beshu.tags.ExcludeESModule.$value")

  lazy val esVersionUsed: String = RorPluginGradleProject.fromSystemProperty.getESVersion
}