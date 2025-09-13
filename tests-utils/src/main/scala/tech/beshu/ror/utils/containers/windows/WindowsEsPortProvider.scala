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

object WindowsEsPortProvider {

  // Node ports need to be predefined, because each ES process must be aware on startup time of ports used by all other cluster nodes.
  // In testcontainers implementation all nodes are identifiable by host name in docker network, with all using the same port. 
  // On Windows, each ES is a process running on the same host, with unique ports.
  val ports: Map[String, WindowsEsPorts] = Map(
    "ROR_SINGLE_1" -> WindowsEsPorts(9200, 9300),
    "ROR1_1" -> WindowsEsPorts(9201, 9301),
    "ROR1_2" -> WindowsEsPorts(9202, 9302),
    "AUDIT_1" -> WindowsEsPorts(9203, 9303),
    "AUDIT_2" -> WindowsEsPorts(9204, 9304),
    "testEsCluster_1" -> WindowsEsPorts(9205, 9305),
    "testEsCluster_2" -> WindowsEsPorts(9206, 9306),
    "ROR2_1" -> WindowsEsPorts(9207, 9307),
    "ROR2_2" -> WindowsEsPorts(9208, 9308),
    "ROR_L1_1" -> WindowsEsPorts(9209, 9309),
    "ROR_L1_2" -> WindowsEsPorts(9210, 9310),
    "XPACK_1" -> WindowsEsPorts(9211, 9311),
    "XPACK_2" -> WindowsEsPorts(9212, 9312),
    "fips_cluster_1" -> WindowsEsPorts(9213, 9313),
    "fips_cluster_2" -> WindowsEsPorts(9214, 9314),
    "ROR_SOURCE_ES_1" -> WindowsEsPorts(9215, 9315),
    "ROR_SOURCE_ES_2" -> WindowsEsPorts(9216, 9316),
    "ROR_DEST_ES_1" -> WindowsEsPorts(9217, 9317),
    "ROR_DEST_ES_2" -> WindowsEsPorts(9218, 9318),
    "ror_xpack_cluster_1" -> WindowsEsPorts(9219, 9319),
    "ror_xpack_cluster_2" -> WindowsEsPorts(9220, 9320),
    "ROR_R1_1" -> WindowsEsPorts(9221, 9321),
    "ROR_R1_2" -> WindowsEsPorts(9222, 9322),
    "ROR_R2_1" -> WindowsEsPorts(9223, 9323),
    "ROR_R2_2" -> WindowsEsPorts(9224, 9324),
    "ROR_1_1" -> WindowsEsPorts(9225, 9325),
    "ROR_1_2" -> WindowsEsPorts(9226, 9326),
    "ROR_2_1" -> WindowsEsPorts(9227, 9327),
    "ROR_2_2" -> WindowsEsPorts(9228, 9328),
    "ROR_3_1" -> WindowsEsPorts(9229, 9329),
    "ROR_3_2" -> WindowsEsPorts(9230, 9330),
    "ror_xpack_cluster_3" -> WindowsEsPorts(9231, 9331),
  )

  def get(nodeName: String): WindowsEsPorts =
    ports.getOrElse(
      nodeName,
      throw new IllegalStateException(
        s"No predefined ports for node $nodeName. Configure port mapping in WindowsEsPortProvider"
      )
    )

  final case class WindowsEsPorts(esPort: Int, transportPort: Int)
}