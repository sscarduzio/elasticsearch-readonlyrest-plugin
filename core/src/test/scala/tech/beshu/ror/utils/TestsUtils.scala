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
package tech.beshu.ror.utils

import java.nio.file.Path
import java.security.{KeyPairGenerator, PrivateKey, PublicKey, SecureRandom}
import java.time.Duration
import java.util.Base64

import better.files.File
import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.Matchers._
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain._
import io.circe.yaml._
import tech.beshu.ror.accesscontrol.blocks.BlockContext.Outcome
import tech.beshu.ror.configuration.RawRorConfig

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

object TestsUtils {

  def basicAuthHeader(value: String): Header =
    Header(Name(NonEmptyString.unsafeFrom("Authorization")), NonEmptyString.unsafeFrom("Basic " + Base64.getEncoder.encodeToString(value.getBytes)))

  def header(name: String, value: String): Header =
    Header(Name(NonEmptyString.unsafeFrom(name)), NonEmptyString.unsafeFrom(value))

  implicit def scalaFiniteDuration2JavaDuration(duration: FiniteDuration): Duration = Duration.ofMillis(duration.toMillis)

  trait BlockContextAssertion {

    def assertBlockContext(expected: BlockContext,
                           current: BlockContext): Unit = {
      assertBlockContext(responseHeaders = expected.responseHeaders,
        contextHeaders = expected.contextHeaders,
        kibanaIndex = expected.kibanaIndex,
        loggedUser = expected.loggedUser,
        currentGroup = expected.currentGroup,
        availableGroups = expected.availableGroups.toSet,
        indices = expected.indices,
        repositories = expected.repositories,
        snapshots = expected.snapshots) {
        current
      }
    }

    def assertBlockContext(responseHeaders: Set[Header] = Set.empty,
                           contextHeaders: Set[Header] = Set.empty,
                           kibanaIndex: Option[IndexName] = None,
                           loggedUser: Option[LoggedUser] = None,
                           currentGroup: Option[Group] = None,
                           availableGroups: Set[Group] = Set.empty,
                           indices: Outcome[Set[IndexName]] = Outcome.NotExist,
                           repositories: Outcome[Set[IndexName]] = Outcome.NotExist,
                           snapshots: Outcome[Set[IndexName]] = Outcome.NotExist)
                          (blockContext: BlockContext): Unit = {
      blockContext.responseHeaders should be(responseHeaders)
      blockContext.contextHeaders should be(contextHeaders)
      blockContext.kibanaIndex should be(kibanaIndex)
      blockContext.loggedUser should be(loggedUser)
      blockContext.currentGroup should be(currentGroup)
      blockContext.availableGroups should be(availableGroups)
      blockContext.indices should be(indices)
      blockContext.repositories should be(repositories)
      blockContext.snapshots should be(snapshots)
    }
  }

  sealed trait AssertionType
  object AssertionType {
    final case class RuleFulfilled(blockContextAssertion: BlockContext => Unit) extends AssertionType
    object RuleRejected extends AssertionType
    final case class RuleThrownException(exception: Throwable) extends AssertionType
  }

  def headerFrom(nameAndValue: (String, String)): Header = {
    (NonEmptyString.unapply(nameAndValue._1), NonEmptyString.unapply(nameAndValue._2)) match {
      case (Some(nameNes), Some(valueNes)) => Header(Name(nameNes), valueNes)
      case _ => throw new IllegalArgumentException(s"Cannot convert ${nameAndValue._1}:${nameAndValue._2} to Header")
    }
  }

  def headerNameFrom(name: String): Header.Name = {
    NonEmptyString.unapply(name) match {
      case Some(nameNes) => Header.Name(nameNes)
      case None => throw new IllegalArgumentException(s"Cannot convert $name to Header.Name")
    }
  }

  def groupFrom(value: String): Group = NonEmptyString.from(value) match {
    case Right(v) => Group(v)
    case Left(_) => throw new IllegalArgumentException(s"Cannot convert $value to Group")
  }

  def apiKeyFrom(value: String): ApiKey = NonEmptyString.from(value) match {
    case Right(v) => ApiKey(v)
    case Left(_) => throw new IllegalArgumentException(s"Cannot convert $value to ApiKey")
  }

  implicit class StringOps(val value: String) extends AnyVal {
    def nonempty: NonEmptyString = NonEmptyString.unsafeFrom(value)
  }

  implicit class NonEmptyListOps[T](val value: T) extends AnyVal {
    def nel: NonEmptyList[T] = NonEmptyList.one(value)
  }

  def generateRsaRandomKeys: (PublicKey, PrivateKey) = {
    val keyGen = KeyPairGenerator.getInstance("RSA")
    val random = SecureRandom.getInstance("SHA1PRNG", "SUN")
    keyGen.initialize(2048, random)
    val pair = keyGen.generateKeyPair()
    (pair.getPublic, pair.getPrivate)
  }

  def rorConfigFrom(yaml: String): RawRorConfig = {
    RawRorConfig(parser.parse(yaml).right.get, yaml)
  }

  def rorConfigFromResource(resource: String): RawRorConfig = {
    rorConfigFrom {
      getResourceContent(resource)
    }
  }

  def getResourcePath(resource: String): Path = {
    File(getClass.getResource(resource).getPath).path
  }

  def getResourceContent(resource: String): String = {
    File(getResourcePath(resource)).contentAsString
  }

}
