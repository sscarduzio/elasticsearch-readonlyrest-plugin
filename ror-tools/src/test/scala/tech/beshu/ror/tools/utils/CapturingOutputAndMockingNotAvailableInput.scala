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
package tech.beshu.ror.tools.utils

import monix.execution.atomic.AtomicAny
import tech.beshu.ror.tools.core.utils.InOut

class CapturingOutputAndMockingNotAvailableInput(mockedInput: Option[String] = None) extends InOut {

  private val outputBuffer: AtomicAny[String] = AtomicAny[String]("")

  override def print(str: String): Unit = outputBuffer.getAndTransform(old => old + str)

  override def println(str: String): Unit = outputBuffer.getAndTransform(old => old + str + "\n")

  override def printErr(str: String): Unit = print(str)

  override def printlnErr(str: String): Unit = println(str)

  override def readLine(): String = null // Simulate running in an environment without configured input stream (for example running inside docker container script)

  def getOutputBuffer: String = outputBuffer.getAndSet("")

}