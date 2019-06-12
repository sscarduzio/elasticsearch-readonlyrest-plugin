package tech.beshu.ror.utils

import java.io.FileInputStream
import java.security.{AccessControlException, AccessController, PrivilegedAction}
import java.util.Base64

import javax.net.ssl.SSLEngine
import org.apache.logging.log4j.LogManager
import tech.beshu.ror.configuration.SslConfiguration
import tech.beshu.ror.settings.SettingsMalformedException


/**
  * Created by sscarduzio on 02/07/2017.
  */
object SSLCertParser {
  private val logger = LogManager.getLogger(classOf[SSLCertParser])

  def validateProtocolAndCiphers(eng: SSLEngine, config: SslConfiguration): Boolean = try {
    val defaultProtocols = eng.getEnabledProtocols
    logger.info("ROR SSL: Available ciphers: " + eng.getEnabledCipherSuites.mkString(","))
    if (config.allowedCiphers.nonEmpty) {
      eng.setEnabledCipherSuites(config.allowedCiphers.map(_.value).toArray)
      logger.info("ROR SSL: Restricting to ciphers: " + eng.getEnabledCipherSuites.mkString(","))
    }
    logger.info("ROR SSL: Available SSL protocols: " + defaultProtocols.mkString(","))
    if (config.allowedProtocols.nonEmpty) {
      eng.setEnabledProtocols(config.allowedProtocols.map(_.value).toArray)
      logger.info("ROR SSL: Restricting to SSL protocols: " + eng.getEnabledProtocols.mkString(","))
    }
    true
  } catch {
    case e: Exception =>
      logger.error("ROR SSL: cannot validate SSL protocols and ciphers! " + e.getClass.getSimpleName + ": " + e.getMessage, e)
      false
  }

  trait SSLContextCreator {
    def mkSSLContext(certChain: String, privateKey: String): Unit
  }

}

class SSLCertParser(val sslConfiguration: SslConfiguration, val creator: SSLCertParser.SSLContextCreator) {
  createContext(sslConfiguration)

  private def createContext(config: SslConfiguration): Unit = {
    SSLCertParser.logger.info("ROR SSL: attempting with JKS keystore..")
    try {
      var keyStorePassBa: Array[Char] = null
      if (config.keystorePassword.isDefined) keyStorePassBa = config.keystorePassword.get.value.toCharArray
      // Load the JKS keystore
      val finKeystoerPassBa = keyStorePassBa
      val ks = java.security.KeyStore.getInstance("JKS")
      AccessController.doPrivileged(new PrivilegedAction[Unit] {
        override def run(): Unit = {
          try {
            val keystoreFile = config.keystoreFile
            ks.load(new FileInputStream(keystoreFile), finKeystoerPassBa)
          } catch {
            case e: Exception =>
              e.printStackTrace()
          }
        }
      })
      var keyPassBa: Array[Char] = null
      if (config.keyPass.isDefined) keyPassBa = config.keyPass.get.value.toCharArray
      // Get PrivKey from keystore
      var sslKeyAlias: String = null
      if (config.keyAlias.isEmpty) if (ks.aliases.hasMoreElements) {
        val inferredAlias = ks.aliases.nextElement
        SSLCertParser.logger.info("ROR SSL: ssl.key_alias not configured, took first alias in keystore: " + inferredAlias)
        sslKeyAlias = inferredAlias
      }
      else throw new SettingsMalformedException("No alias found, therefore key found in keystore!")
      else sslKeyAlias = config.keyAlias.get.value
      val key = ks.getKey(sslKeyAlias, keyPassBa)
      if (key == null) throw new SettingsMalformedException("Private key not found in keystore for alias: " + sslKeyAlias)
      // Create a PEM of the private key
      var sb = new StringBuilder
      sb.append("---BEGIN PRIVATE KEY---\n")
      sb.append(Base64.getEncoder.encodeToString(key.getEncoded))
      sb.append("\n")
      sb.append("---END PRIVATE KEY---")
      val privateKey = sb.toString
      SSLCertParser.logger.info("ROR SSL: Discovered key from JKS")
      // Get CertChain from keystore
      val cchain = ks.getCertificateChain(sslKeyAlias)
      // Create a PEM of the certificate chain
      sb = new StringBuilder
      for (c <- cchain) {
        sb.append("-----BEGIN CERTIFICATE-----\n")
        sb.append(Base64.getEncoder.encodeToString(c.getEncoded))
        sb.append("\n")
        sb.append("-----END CERTIFICATE-----\n")
      }
      val certChain = sb.toString
      SSLCertParser.logger.info("ROR SSL: Discovered cert chain from JKS")
      AccessController.doPrivileged(new PrivilegedAction[Void] {
        override def run(): Void = {
          creator.mkSSLContext(certChain, privateKey)
          null
        }
      })
    } catch {
      case t: Throwable =>
        SSLCertParser.logger.error("ROR SSL: Failed to load SSL certs and keys from JKS Keystore! " + t.getClass.getSimpleName + ": " + t.getMessage, t)
        if (t.isInstanceOf[AccessControlException]) SSLCertParser.logger.error("ROR SSL: Check the JKS Keystore path is correct: " + config.keystoreFile.getAbsolutePath)
        t.printStackTrace()
    }
  }
}
