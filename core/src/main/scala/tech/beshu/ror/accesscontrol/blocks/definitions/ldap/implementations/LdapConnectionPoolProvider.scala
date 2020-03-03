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
package tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations

import cats.data.NonEmptyList
import cats.effect.Resource
import cats.implicits._
import com.unboundid.ldap.sdk._
import com.unboundid.util.ssl.{SSLUtil, TrustAllTrustManager}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.LdapConnectionConfig._
import tech.beshu.ror.utils.ScalaOps.retry

import scala.util.control.NonFatal
trait LdapConnectionPoolProvider{
  def connect(connectionConfig: LdapConnectionConfig): Task[LDAPConnectionPool]
  def close(): Task[Unit]
}
