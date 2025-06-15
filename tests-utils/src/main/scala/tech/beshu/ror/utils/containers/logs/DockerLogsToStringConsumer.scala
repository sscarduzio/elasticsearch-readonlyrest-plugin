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
package tech.beshu.ror.utils.containers.logs

import org.testcontainers.containers.output.OutputFrame

import java.util.function.Consumer
import scala.language.postfixOps

class DockerLogsToStringConsumer() extends Consumer[OutputFrame] {

  private val buffer = new StringBuilder()

  override def accept(frame: OutputFrame): Unit = synchronized {
    if (frame != null && frame.getUtf8String != null) {
      buffer.append(frame.getUtf8String)
    }
  }

  def getLogs: String = synchronized {
    val result = buffer.toString()
    buffer.clear()
    result
  }

}
