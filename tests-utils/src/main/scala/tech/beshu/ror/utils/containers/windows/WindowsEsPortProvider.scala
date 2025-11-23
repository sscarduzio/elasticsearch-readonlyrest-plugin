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
package tech.beshu.ror.utils.containers.windows

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.ListMap

object WindowsEsPortProvider {

  private val esPortProvider = new BoundedAtomicInt(start = 9200, max = 9299)

  // Node ports need to be predefined, because each ES process must be aware on startup time of ports used by all other cluster nodes.
  // In testcontainers implementation all nodes are identifiable by host name in docker network, with all using the same port. 
  // On Windows, each ES is a process running on the same host, with unique ports.
  val esNodeNameToEsPorts: Map[String, WindowsEsPorts] =
    ListMap.from(
      List(
        "AUDIT_1",
        "AUDIT_2",
        "ROR1_1",
        "ROR1_2",
        "ROR2_1",
        "ROR2_2",
        "ROR_L1_1",
        "ROR_L1_2",
        "ROR_SINGLE_1",
        "ROR_SOURCE_ES_1",
        "ROR_SOURCE_ES_2",
        "ROR_DEST_ES_1",
        "ROR_DEST_ES_2",
        "ROR_R1_1",
        "ROR_R1_2",
        "ROR_R2_1",
        "ROR_R2_2",
        "ROR_1_1",
        "ROR_1_2",
        "ROR_2_1",
        "ROR_2_2",
        "ROR_3_1",
        "ROR_3_2",
        "XPACK_1",
        "XPACK_2",
        "fips_cluster_1",
        "fips_cluster_2",
        "ror_xpack_cluster_1",
        "ror_xpack_cluster_2",
        "ror_xpack_cluster_3",
        "testEsCluster_1",
        "testEsCluster_2",
      ).map(nodeName => (nodeName, nextPorts))
    )

  def get(nodeName: String): WindowsEsPorts =
    esNodeNameToEsPorts.getOrElse(
      nodeName,
      throw new IllegalStateException(
        s"No predefined ports for node $nodeName. Configure port mapping in WindowsEsPortProvider"
      )
    )

  private def nextPorts = {
    val nextAvailablePort = esPortProvider.next()
    WindowsEsPorts(esPort = nextAvailablePort, transportPort = nextAvailablePort + 100)
  }

  final case class WindowsEsPorts(esPort: Int, transportPort: Int)

  private class BoundedAtomicInt(start: Int, max: Int) {
    private val atomic = new AtomicInteger(start)

    def next(): Int = {
      val value = atomic.getAndIncrement()
      if (value > max)
        throw new IllegalStateException(s"Limit exceeded: $value > $max")
      value
    }
  }
}