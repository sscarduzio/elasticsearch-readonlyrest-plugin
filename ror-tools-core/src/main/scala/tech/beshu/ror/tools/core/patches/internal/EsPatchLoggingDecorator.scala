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
package tech.beshu.ror.tools.core.patches.internal

import tech.beshu.ror.tools.core.patches.base.EsPatch
import tech.beshu.ror.tools.core.patches.base.EsPatch.IsPatched

import scala.util.{Failure, Success, Try}

private[patches] class EsPatchLoggingDecorator(underlying: EsPatch)
  extends EsPatch {

  override def isPatched: IsPatched = {
    println("Checking if Elasticsearch is patched ...")
    underlying.isPatched
  }

  override def backup(): Unit = {
    println("Creating backup ...")
    underlying.backup()
  }

  override def restore(): Unit = {
    println("Restoring ...")
    Try(underlying.restore()) match {
      case Success(()) =>
        println("Elasticsearch is unpatched! ReadonlyREST can be removed now")
      case Failure(exception) =>
        throw exception
    }
  }

  override def execute(): Unit = {
    println("Patching ...")
    Try(underlying.execute()) match {
      case Success(()) =>
        println("Elasticsearch is patched! ReadonlyREST is ready to use")
      case Failure(exception) =>
        throw exception
    }
  }
}
