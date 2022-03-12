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
package tech.beshu.ror.tools

import tech.beshu.ror.tools.patches.EsPatch

import scala.language.postfixOps

object actions {

  class PatchAction(patch: EsPatch) {
    def execute(): Unit = {
      (for {
        _ <- patch.assertIsNotPatched()
        _ <- patch.backup()
        _ <- patch.execute()
      } yield ()) get
    }
  }
}

class UnpatchAction(patch: EsPatch) {
  def execute(): Unit = {
    (for {
      _ <- patch.assertIsPatched()
      _ <- patch.restore()
    } yield ()) get
  }
}

class VerifyAction(patch: EsPatch) {
  def execute(): Unit = {
    patch.assertIsPatched().get
  }
}
