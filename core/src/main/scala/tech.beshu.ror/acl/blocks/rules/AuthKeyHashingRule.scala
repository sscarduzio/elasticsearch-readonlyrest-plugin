package tech.beshu.ror.acl.blocks.rules

import java.nio.charset.Charset

import com.google.common.hash.{HashFunction, Hashing}
import com.typesafe.scalalogging.StrictLogging
import tech.beshu.ror.acl.utils.BasicAuthOps._
import tech.beshu.ror.acl.utils.TryOps._
import tech.beshu.ror.utils.BasicAuthUtils

class AuthKeyHashingRule(settings: BasicAuthenticationRule.Settings,
                         hashFunction: HashFunction)
  extends BasicAuthenticationRule(settings)
    with StrictLogging {

  override protected def authenticate(configuredAuthKey: String,
                                      basicAuth: BasicAuthUtils.BasicAuth): Boolean = {
    basicAuth
      .tryDecode
      .map { decodedProvided =>
        val shaProvided: String = hashFunction.hashString(decodedProvided, Charset.defaultCharset).toString
        configuredAuthKey == shaProvided
      }
      .getOr { ex =>
        logger.warn("Exception while authentication", ex)
        false
      }
  }
}

class AuthKeySha1Rule(settings: BasicAuthenticationRule.Settings)
  extends AuthKeyHashingRule(settings, hashFunction = Hashing.sha1())


class AuthKeySha256Rule(settings: BasicAuthenticationRule.Settings)
  extends AuthKeyHashingRule(settings, hashFunction = Hashing.sha256())

class AuthKeySha512Rule(settings: BasicAuthenticationRule.Settings)
  extends AuthKeyHashingRule(settings, hashFunction = Hashing.sha512())