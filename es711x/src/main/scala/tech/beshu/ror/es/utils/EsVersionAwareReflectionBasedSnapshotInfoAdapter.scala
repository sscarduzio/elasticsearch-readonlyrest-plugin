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
package tech.beshu.ror.es.utils

import org.elasticsearch.Version
import org.elasticsearch.snapshots.SnapshotInfo
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.set.CovariantSet.*
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import org.joor.Reflect.on
import java.util.List as JList

import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

class EsVersionAwareReflectionBasedSnapshotInfoAdapter(val snapshotInfo: SnapshotInfo) {

  def featureStatesIndices(): Set[String] = {
    if (Version.CURRENT.before(Version.fromString("7.12.0"))) {
      Set.empty
    } else {
      featureStatesIndicesForEsGreaterOrEqual7120()
    }
  }

  private def featureStatesIndicesForEsGreaterOrEqual7120() = {
    doPrivileged {
      val featureStates = on(snapshotInfo).call("featureStates").get[JList[AnyRef]].asScala
      featureStates
        .flatMap { featureState =>
          on(featureState).call("getIndices").get[JList[String]].asScala
        }
        .toCovariantSet
    }
  }
}
object EsVersionAwareReflectionBasedSnapshotInfoAdapter {
  implicit def toAdapter(snapshotInfo: SnapshotInfo): EsVersionAwareReflectionBasedSnapshotInfoAdapter =
    new EsVersionAwareReflectionBasedSnapshotInfoAdapter(snapshotInfo)
}
