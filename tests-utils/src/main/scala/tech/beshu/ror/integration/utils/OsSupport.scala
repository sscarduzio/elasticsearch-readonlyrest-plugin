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

import org.scalatest.Tag
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.wordspec.AnyWordSpecLike
import tech.beshu.ror.utils.misc.{EsModulePatterns, OsUtils}

trait OsSupportForAnyWordSpecLike extends OsSupport {
  this: AnyWordSpecLike =>

  override type T = ResultOfTaggedAsInvocationOnString

  override def stringTaggedAs(string: String, firstTestTag: Tag, otherTestTags: Tag*): T =
    string.taggedAs(firstTestTag, otherTestTags: _*)
}

trait OsSupportForAnyFreeSpecLike extends OsSupport with AnyFreeSpecLike {

  override type T = ResultOfTaggedAsInvocationOnString

  override def stringTaggedAs(string: String, firstTestTag: Tag, otherTestTags: Tag*): T =
    string.taggedAs(firstTestTag, otherTestTags: _*)
}

sealed trait OsSupport extends EsModulePatterns {

  type T

  protected def stringTaggedAs(string: String, firstTestTag: Tag, otherTestTags: Tag*): T

  implicit final class OsSupportOps(string: String) {

    def ignoreOnWindows: T = {
      if (OsUtils.isWindows) stringTaggedAs(string, IgnoreOnWindows)
      else string.asInstanceOf[T]
    }

  }

  object IgnoreOnWindows extends Tag("tech.beshu.tags.IgnoreOnWindows")

}
