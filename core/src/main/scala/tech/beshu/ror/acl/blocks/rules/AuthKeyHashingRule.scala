package tech.beshu.ror.acl.blocks.rules

import java.nio.charset.Charset

import cats.implicits._
import com.google.common.hash.{HashFunction, Hashing}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.utils.BasicAuthOps._
import tech.beshu.ror.acl.utils.ScalaExt._
import tech.beshu.ror.acl.aDomain.AuthData
import tech.beshu.ror.acl.aDomain.AuthData._
import tech.beshu.ror.utils.BasicAuthUtils

abstract class AuthKeyHashingRule(settings: BasicAuthenticationRule.Settings,
                                  hashFunction: HashFunction)
  extends BasicAuthenticationRule(settings)
    with Logging {

  override protected def compare(configuredAuthKey: AuthData,
                                 basicAuth: BasicAuthUtils.BasicAuth): Task[Boolean] = Task {
    basicAuth
      .tryDecode
      .map { decodedProvided =>
        val shaProvided = AuthData(hashFunction.hashString(decodedProvided.value, Charset.defaultCharset).toString)
        configuredAuthKey === shaProvided
      }
      .getOr { ex =>
        logger.warn("Exception while authentication", ex)
        false
      }
  }
}

class AuthKeySha1Rule(settings: BasicAuthenticationRule.Settings)
  extends AuthKeyHashingRule(settings, hashFunction = Hashing.sha1()) {

  override val name: Rule.Name = AuthKeySha1Rule.name
}

object AuthKeySha1Rule {
  val name = Rule.Name("auth_key_sha1")
}

class AuthKeySha256Rule(settings: BasicAuthenticationRule.Settings)
  extends AuthKeyHashingRule(settings, hashFunction = Hashing.sha256()) {

  override val name: Rule.Name = AuthKeySha256Rule.name
}

object AuthKeySha256Rule {
  val name = Rule.Name("auth_key_sha256")
}

class AuthKeySha512Rule(settings: BasicAuthenticationRule.Settings)
  extends AuthKeyHashingRule(settings, hashFunction = Hashing.sha512()) {

  override val name: Rule.Name = AuthKeySha512Rule.name
}

object AuthKeySha512Rule {
  val name = Rule.Name("auth_key_sha512")
}