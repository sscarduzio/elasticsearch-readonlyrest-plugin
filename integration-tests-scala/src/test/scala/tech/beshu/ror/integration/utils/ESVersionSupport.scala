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

import org.scalatest.{Tag, WordSpecLike}

import scala.language.implicitConversions

trait ESVersionSupport extends WordSpecLike {

  implicit final class ESVersionSupportOps(string: String) {
    def excludeES(esVersion: String, esVersions: String*): ResultOfTaggedAsInvocationOnString = {
      string.taggedAs(new ExcludeESModule(esVersion), esVersions.map(new ExcludeESModule(_)).toList: _*)
    }
  }

  private final class ExcludeESModule(value: String) extends Tag(s"tech.beshu.tags.ExcludeESModule.$value")
}
