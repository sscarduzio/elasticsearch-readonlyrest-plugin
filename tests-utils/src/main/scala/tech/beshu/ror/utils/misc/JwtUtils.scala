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
package tech.beshu.ror.utils.misc

import java.security.PrivateKey

import cats.data.NonEmptyList
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.impl.DefaultClaims

import scala.collection.JavaConverters._
import scala.language.implicitConversions

object JwtUtils {

  def createJsonWebToken(secret: PrivateKey, claims: Traversable[Claim]): String = {
    // todo: cleanup
    val defaultClaims = new DefaultClaims {
      claims.foldLeft(Map.empty[String, AnyRef]) {
        case (acc, claim) =>
          val claimValue = claim.name.tail match {
            case Nil =>
              claim.value
            case notEmptyTail =>
              val reversedListOfClaimKeys = notEmptyTail.reverse
              val theDeepestElement: AnyRef = Map(reversedListOfClaimKeys.head.value -> claim.value).asJava
              val claimValue = reversedListOfClaimKeys.tail.foldLeft(theDeepestElement) {
                case (innerElement, claimKey) => Map(claimKey.value -> innerElement).asJava
              }
              claimValue
          }
          acc + (claim.name.head.value -> claimValue)
      }
    }
    Jwts.builder
      .signWith(secret)
      .setSubject("test")
      .setClaims(defaultClaims)
      .compact()
  }

  final case class Claim(name: NonEmptyList[ClaimKey], value: String)
  final case class ClaimKey(value: String) extends AnyVal

  implicit class NonEmptyListOfClaimKeysOps[T](val list: T)
                                              (implicit f: T => NonEmptyList[ClaimKey]) {
    def :=(value: String): Claim = Claim(list, value)
    def :->(nextKey: ClaimKey): NonEmptyList[ClaimKey] = list :+ nextKey
  }

  implicit class ClaimKeyOps[T](key: T)
                               (implicit f: T => ClaimKey) {
    def :=(value: String): Claim = Claim(NonEmptyList.one(key), value)
    def :->(nextKey: ClaimKey): NonEmptyList[ClaimKey] = NonEmptyList.of(key, nextKey)
  }

  implicit def string2ClaimKey(value: String): ClaimKey = ClaimKey(value)

}
