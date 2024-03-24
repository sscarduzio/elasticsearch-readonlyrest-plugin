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
package tech.beshu.ror.utils.misc

import tech.beshu.ror.utils.gradle.RorPluginGradleProject

import scala.util.matching.Regex

final case class EsModule(name: String)
object EsModule {

  def doesCurrentModuleMatch(esModuleRegex: Regex): Boolean = {
    doesEsModuleMatchRegex(currentEsModule, esModuleRegex :: Nil)
  }

  def isCurrentModuleExcluded(excludedEsModulePatterns: Regex, otherExcludedEsModulePatterns: Regex*): Boolean = {
    doesEsModuleMatchRegex(currentEsModule, excludedEsModulePatterns :: otherExcludedEsModulePatterns.toList)
  }

  def isCurrentModuleNotExcluded(excludedEsModulePatterns: Regex, otherExcludedEsModulePatterns: Regex*): Boolean = {
    !isCurrentModuleExcluded(excludedEsModulePatterns, otherExcludedEsModulePatterns: _*)
  }

  def getExcludedModuleNames(excludedEsModulePatterns: Regex, otherExcludedEsModulePatterns: Regex*): List[EsModule] = {
    RorPluginGradleProject
      .availableEsModules
      .map(EsModule.apply)
      .filter { esModule =>
        doesEsModuleMatchRegex(esModule, excludedEsModulePatterns :: otherExcludedEsModulePatterns.toList)
      }
  }

  private def doesEsModuleMatchRegex(esModule: EsModule, regexes: Iterable[Regex]): Boolean = {
    regexes.toList.exists(_.findFirstIn(esModule.name).isDefined)
  }

  private def currentEsModule = EsModule(RorPluginGradleProject.fromSystemProperty.moduleName)
}

trait EsModulePatterns {
  val allEs6x = "^es6\\dx$".r
  val allEs7x = "^es7\\d+x$".r
  val allEs7xBelowEs74x = "^es7[0-3]x$".r
  val allEs7xBelowEs77x = "^es7[0-6]x$".r
  val allEs7xBelowEs78x = "^es7[0-7]x$".r
  val allEs7xBelowEs79x = "^es7[0-8]x$".r
  val allEs7xBelowEs716x = "^es7(0?[0-9]|1[0-5])x$".r
  val allEs7xBelowEs714x = "^es7(0?[0-9]|1[0-3])x$".r
  val allEs7xBelowEs711x = "^es7(0?[0-9]|10)x$".r
  val allEs8x = "^es8\\d+x$".r
  val allEs8xBelowEs87x = "^es8[0-6]x$".r
  val allEs8xAboveEs86x = "^es8([7-9]|[1-9][0-9])*x$".r

}