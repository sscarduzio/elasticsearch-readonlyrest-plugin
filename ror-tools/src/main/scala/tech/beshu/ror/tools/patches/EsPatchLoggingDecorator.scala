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
package tech.beshu.ror.tools.patches

class EsPatchLoggingDecorator(underlying: EsPatch) extends EsPatch {

  override def isPatched: Boolean = {
    println("Checking if ES is patched ...")
    underlying.isPatched
  }

  override def backup(): Unit = {
    println("Creating backup ...")
    underlying.backup()
  }

  override def restore(): Unit = {
    println("Restoring ...")
    underlying.restore()
    println("ES is unpatched! ReadonlyREST can be removed now")
  }

  override def execute(): Unit = {
    println("Patching ...")
    underlying.execute()
    println("ES is patched! ReadonlyREST is ready to use")
  }
}
