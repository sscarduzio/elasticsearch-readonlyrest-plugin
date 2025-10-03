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
package tech.beshu.ror.utils.containers

import com.dimafeng.testcontainers.SingleContainer
import org.testcontainers.containers.GenericContainer
import tech.beshu.ror.utils.containers.windows.WindowsPseudoWiremockContainer
import tech.beshu.ror.utils.misc.OsUtils
import tech.beshu.ror.utils.misc.OsUtils.CurrentOs

object Wiremock {

  def create(mappings: List[String], portWhenRunningOnWindows: Int = 8080): WiremockContainer = {
    OsUtils.currentOs match {
      case CurrentOs.Windows =>
        new WiremockContainer(
          container = new WindowsPseudoWiremockContainer(portWhenRunningOnWindows, mappings),
          host = "localhost",
          portProvider = () => portWhenRunningOnWindows,
          originalPort = portWhenRunningOnWindows,
        )
      case CurrentOs.OtherThanWindows =>
        val container = new WireMockScalaAdapter(WireMockContainer.create(mappings: _*))
        new WiremockContainer(
          container = container,
          host = container.getWireMockHost,
          portProvider = () => container.getWireMockPort,
          originalPort = WireMockContainer.WIRE_MOCK_PORT,
        )
    }
  }

  class WiremockContainer(val container: SingleContainer[GenericContainer[_]],
                          val host: String,
                          val portProvider: WiremockPortProvider,
                          val originalPort: Int)

  trait WiremockPortProvider {
    def providePort(): Int
  }

}
