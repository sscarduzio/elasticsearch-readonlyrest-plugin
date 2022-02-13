package tech.beshu.ror.boot

import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import tech.beshu.ror.configuration.FipsConfiguration
import tech.beshu.ror.configuration.FipsConfiguration.FipsMode
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import java.security.Security

object SecurityProviderConfiguratorForFips {

  def configureIfRequired(fipsConfiguration: FipsConfiguration): Unit = {
    fipsConfiguration.fipsMode match {
      case FipsMode.SslOnly =>
        doPrivileged {
          Security.insertProviderAt(new BouncyCastleFipsProvider(), 1) // basic encryption provider
          Security.insertProviderAt(new BouncyCastleJsseProvider("fips:BCFIPS"), 2) // tls
          Security.removeProvider("SunRsaSign")
          Security.removeProvider("SunJSSE")
        }
      case FipsMode.NonFips =>
    }
  }
}
