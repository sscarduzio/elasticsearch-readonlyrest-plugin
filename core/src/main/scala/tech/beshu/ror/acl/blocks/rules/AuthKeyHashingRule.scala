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
package tech.beshu.ror.acl.blocks.rules

import java.nio.charset.Charset

import cats.implicits._
import com.google.common.hash.{HashFunction, Hashing}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.domain.Secret._
import tech.beshu.ror.acl.domain.{BasicAuth, Secret}

abstract class AuthKeyHashingRule(settings: BasicAuthenticationRule.Settings,
                                  hashFunction: HashFunction)
  extends BasicAuthenticationRule(settings)
    with Logging {

  override protected def compare(configuredAuthKey: Secret,
                                 basicAuth: BasicAuth): Task[Boolean] = Task {
    val shaProvided = Secret(hashFunction.hashString(basicAuth.colonSeparatedString, Charset.defaultCharset).toString)
    configuredAuthKey === shaProvided
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