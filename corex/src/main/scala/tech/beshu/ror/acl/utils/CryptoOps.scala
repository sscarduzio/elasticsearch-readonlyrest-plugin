package tech.beshu.ror.acl.utils

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

import org.apache.commons.codec.binary.Base64

import scala.util.Try

object CryptoOps {

  def keyStringToPublicKey(algorithm: String, key: String) = Try {
    val keyBytes = Base64.decodeBase64(key)
    val kf = KeyFactory.getInstance(algorithm)
    kf.generatePublic(new X509EncodedKeySpec(keyBytes))
  }
}
