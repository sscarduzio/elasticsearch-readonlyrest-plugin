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
package tech.beshu.ror.utils.elasticsearch

import org.apache.http.HttpResponse
import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPost, HttpPut}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse}
import tech.beshu.ror.utils.elasticsearch.SnapshotManager.RepositoriesResult
import tech.beshu.ror.utils.httpclient.RestClient

class SnapshotManager(client: RestClient)
  extends BaseManager(client) {

  def getRepository(repositoryNamePattern: String,
                    otherRepositoryNamePatterns: String*): RepositoriesResult = {
    call(
      createGetRepositoriesRequest(repositoryNamePattern :: otherRepositoryNamePatterns.toList),
      new RepositoriesResult(_)
    )
  }

  def getAllRepositories(): RepositoriesResult = {
    call(createGetRepositoriesRequest(Nil), new RepositoriesResult(_))
  }

  def putRepository(repositoryName: String): JsonResponse = {
    call(createNewRepositoryRequest(repositoryName), new JsonResponse(_))
  }

  def deleteRepository(repositoryName: String): JsonResponse = {
    call(createDeleteRepositoryRequest(repositoryName), new JsonResponse(_))
  }

  def verifyRepository(repositoryName: String): JsonResponse = {
    call(createVerifyRepositoryRequest(repositoryName), new JsonResponse(_))
  }

  def cleanUpRepository(repositoryName: String): JsonResponse = {
    call(createCleanUpRepositoryRequest(repositoryName), new JsonResponse(_))
  }

  def deleteAllSnapshots(): JsonResponse = {
    call(createDeleteAllSnapshotsRequest(), new JsonResponse(_))
  }

  private def createNewRepositoryRequest(name: String) = {
    val request = new HttpPut(client.from(s"/_snapshot/$name"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""
         |{
         |  "type": "fs",
         |  "settings": {
         |    "location": "/tmp"
         |  }
         |}""".stripMargin
    ))
    request
  }

  private def createDeleteRepositoryRequest(name: String) = {
    new HttpDelete(client.from(s"/_snapshot/$name"))
  }

  private def createVerifyRepositoryRequest(name: String) = {
    new HttpPost(client.from(s"/_snapshot/$name/_verify"))
  }

  private def createCleanUpRepositoryRequest(name: String) = {
    new HttpPost(client.from(s"/_snapshot/$name/_cleanup"))
  }

  private def createDeleteAllSnapshotsRequest() = {
    new HttpDelete(client.from("/_snapshot/*"))
  }

  private def createGetRepositoriesRequest(repositoriesPatterns: List[String]) = {
    val namesStr = repositoriesPatterns match {
      case Nil => "*"
      case all => all.mkString(",")
    }
    new HttpGet(client.from(s"/_snapshot/$namesStr"))
  }
}

object SnapshotManager {

  class RepositoriesResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val repositories: Map[String, JSON] = responseJson.obj.toMap
  }
}
