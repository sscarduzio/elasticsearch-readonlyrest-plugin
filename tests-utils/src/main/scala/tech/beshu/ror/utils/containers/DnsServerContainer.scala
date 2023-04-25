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

import com.dimafeng.testcontainers.GenericContainer
import com.github.dockerjava.api.model.{ExposedPort, InternetProtocol}
import org.testcontainers.images.builder.ImageFromDockerfile

import scala.jdk.CollectionConverters._

class DnsServerContainer(srvServicePort: Int)
  extends GenericContainer(
    dockerImage = new ImageFromDockerfile()
      .withFileFromClasspath("Dockerfile", "coredns-image/Dockerfile")
      .withFileFromClasspath("Corefile", "coredns-image/Corefile")
      .withFileFromString("conf",
      s"""
          |$$ORIGIN example.org.
          |@	3600	IN	SOA someorg.org.  someorg.com.  (2017042745 7200 3600 1209600 3600)
          |_ldap._tcp.	 86400	IN	SRV	10	60     $srvServicePort	localhost.
          |""".stripMargin)
  ) {

  def dnsPort: Int = {
    // This is hack to obtain mapping of UDP port as testcontainers doesn't allow explicit mapping of UDP ports
    // although it is mapping each port exposed by container(even UDP).
    this.containerInfo
      .getNetworkSettings
      .getPorts
      .getBindings
      .asScala
      .getOrElse(new ExposedPort(53, InternetProtocol.UDP), throw new Exception("Couldn't get value of docker mapped port 53/udp"))
      .headOption
      .getOrElse(throw new Exception("Couldn't get first value of docker mapped port 53/udp"))
      .getHostPortSpec
      .toInt
  }

  override def stop(): Unit = {
    this.container.stop()
  }

}