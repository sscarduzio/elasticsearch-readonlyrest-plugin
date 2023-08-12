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
package tech.beshu.ror.es.utils

import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.TransportVersion
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.io.stream.BytesStreamOutput
import tech.beshu.ror.accesscontrol.domain.Header

import java.util.Base64
import scala.jdk.CollectionConverters._

object XPackSecurityAuthenticationHeader {

  def createRorUserAuthenticationHeader(nodeName: String) = new Header(
    Header.Name("_xpack_security_authentication"),
    getAuthenticationHeaderValue(nodeName, "ROR", isInternal = false)
  )

  def createXpackSecurityAuthenticationHeader(nodeName: String) = new Header(
    Header.Name("_xpack_security_authentication"),
    getAuthenticationHeaderValue(nodeName, "_xpack_security", isInternal = true)
  )

  def createSystemAuthenticationHeader(nodeName: String) = new Header(
    Header.Name("_xpack_security_authentication"),
    getAuthenticationHeaderValue(nodeName, "_system", isInternal = true)
  )

  private def getAuthenticationHeaderValue(nodeName: String, userName: String, isInternal: Boolean): NonEmptyString = {
    val output = new BytesStreamOutput()
    val currentVersion = TransportVersion.CURRENT
    output.setTransportVersion(currentVersion)
    TransportVersion.writeVersion(currentVersion, output)
    output.writeBoolean(isInternal)
    if(isInternal) {
      output.writeString(userName)
    } else {
      output.writeString(userName)
      output.writeStringArray(Array("kibana_admin", "superuser"))
      output.writeGenericMap(Map.empty[String, AnyRef].asJava)
      output.writeOptionalString(null)
      output.writeOptionalString(null)
      output.writeBoolean(true)
      output.writeBoolean(false)
    }
    output.writeString(nodeName)
    output.writeString("__attach")
    output.writeString("__attach")
    if(output.getTransportVersion.onOrAfter(TransportVersion.V_8_2_0)) {
      output.writeBoolean(false)
    }
    output.writeBoolean(false)
    if (output.getTransportVersion.onOrAfter(TransportVersion.V_7_0_0)) {
      output.writeVInt(4) // Internal
      if(output.getTransportVersion.onOrAfter(TransportVersion.V_8_8_0)) {
        output.writeVInt(0)
      } else {
        output.writeGenericMap(Map.empty[String, Object].asJava)
      }
    }
    NonEmptyString.unsafeFrom {
      Base64.getEncoder.encodeToString(BytesReference.toBytes(output.bytes()))
    }
  }
}
