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

import monix.eval.Task
import org.elasticsearch.Version
import org.elasticsearch.repositories.RepositoryData
import org.elasticsearch.snapshots.SnapshotsService
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

class EsVersionAwareReflectionBasedSnapshotServiceAdapter(snapshotsService: SnapshotsService) {

  def getRepositoryData(repository: RepositoryName): Task[RepositoryData] = {
    if(Version.CURRENT.before(Version.fromString("7.6.0")))
      getRepositoriesDataForEsPre76x(repository)
    else
      getRepositoriesDataForPostEs76x(repository)
  }

  private def getRepositoriesDataForPostEs76x(repository: RepositoryName): Task[RepositoryData] = {
    val listener = new ActionListenerToTaskAdapter[RepositoryData]()
    doPrivileged {
      on(snapshotsService).call("getRepositoryData", RepositoryName.toString(repository), listener)
    }
    listener.result
  }

  private def getRepositoriesDataForEsPre76x(repository: RepositoryName): Task[RepositoryData] =
    Task.delay {
      doPrivileged {
        on(snapshotsService)
          .call("getRepositoryData", RepositoryName.toString(repository))
          .get[RepositoryData]
      }
    }
}
