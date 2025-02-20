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
package tech.beshu.ror.es

import tech.beshu.ror.com.fasterxml.jackson.core.Version

final case class EsVersion(major: Int, minor: Int, revision: Int) extends Ordered[EsVersion] {
  override def compare(that: EsVersion): Int = summon[Ordering[EsVersion]].compare(this, that)

  def formatted: String = s"$major.$minor.$revision"
}

object EsVersion {
  given Ordering[EsVersion] = Ordering.by[EsVersion, Version](
    esVersion => Version(esVersion.major, esVersion.minor, esVersion.revision, "", "", "")
  )(Ordering.by(identity))
}