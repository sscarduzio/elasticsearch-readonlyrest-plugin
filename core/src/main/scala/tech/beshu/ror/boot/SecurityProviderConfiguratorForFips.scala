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
package tech.beshu.ror.boot

import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import tech.beshu.ror.configuration.RorSsl
import tech.beshu.ror.configuration.SslConfiguration.FipsMode.{NonFips, SslOnly}
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import java.security.Security

object SecurityProviderConfiguratorForFips {

  def configureIfRequired(ssl: RorSsl): Unit = {
    val fipsModes = ssl match {
      case RorSsl.OnlyExternalSslConfiguration(ssl) => ssl.fipsMode :: Nil
      case RorSsl.OnlyInternodeSslConfiguration(ssl) => ssl.fipsMode :: Nil
      case RorSsl.ExternalAndInternodeSslConfiguration(external, internode) => external.fipsMode :: internode.fipsMode :: Nil
    }
    fipsModes
      .find {
        case SslOnly => true
        case NonFips => false
      }
      .foreach { _ =>
        doPrivileged {
          Security.insertProviderAt(new BouncyCastleFipsProvider(), 1) // basic encryption provider
          Security.insertProviderAt(new BouncyCastleJsseProvider("fips:BCFIPS"), 2) // tls
        }
      }
  }
}
