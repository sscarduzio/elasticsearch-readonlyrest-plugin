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

import com.typesafe.scalalogging.LazyLogging
import org.apache.http.HttpResponse
import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPost, HttpPut}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse}
import tech.beshu.ror.utils.elasticsearch.SnapshotManager.{RepositoriesResult, SnapshotsResult}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.collection.JavaConverters._

class SnapshotManager(client: RestClient)
  extends BaseManager(client)
    with LazyLogging {

  def getRepository(repositoryNamePattern: String,
                    otherRepositoryNamePatterns: String*): RepositoriesResult = {
    call(
      createGetRepositoriesRequest(repositoryNamePattern :: otherRepositoryNamePatterns.toList),
      new RepositoriesResult(_)
    )
  }

  def getAllRepositories: RepositoriesResult = {
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

  def deleteAllRepositories(): JsonResponse = {
    call(createDeleteAllRepositoriesRequest(), new JsonResponse(_))
  }

  def getAllSnapshotsOf(repositoryName: String): SnapshotsResult = {
    call(createGetSnapshotsRequest(repositoryName, Nil), new SnapshotsResult(_))
  }

  def getSnapshotsOf(repositoryName: String, snapshots: String*): SnapshotsResult = {
    call(createGetSnapshotsRequest(repositoryName, snapshots.toList), new SnapshotsResult(_))
  }

  def getAllSnapshotStatusesOf(repositoryName: String, snapshot: String, snapshots: String*): SnapshotsResult = {
    call(createGetSnapshotStatusesRequest(repositoryName, snapshot :: snapshots.toList), new SnapshotsResult(_))
  }

  def getAllSnapshotStatuses(): SnapshotsResult = {
    call(createGetAllSnapshotStatusesRequest(), new SnapshotsResult(_))
  }

  def putSnapshot(repositoryName: String, snapshotName: String, index: String, otherIndices: String*): JsonResponse = {
    call(createNewSnapshotRequest(repositoryName, snapshotName, index :: otherIndices.toList), new JsonResponse(_))
  }

  def deleteSnapshotsOf(repositoryName: String, snapshots: String*): JsonResponse = {
    call(createDeleteSnapshotsOfRequest(repositoryName, snapshots.toList), new JsonResponse(_))
  }

  def deleteSnapshotsOf(repositoryName: String): JsonResponse = {
    call(createDeleteSnapshotsOfRequest(repositoryName, Nil), new JsonResponse(_))
  }

  def deleteAllSnapshots(): Unit = {
    val repositoriesResult = getAllRepositories
    repositoriesResult.force()
    repositoriesResult.repositories.foreach { case (repository, _) =>
      val snapshotsResult = getAllSnapshotsOf(repository)
      snapshotsResult.force()
      snapshotsResult
        .snapshots
        .map { json => json("snapshot").str }
        .foreach { snapshot =>
          deleteSnapshotsOf(repository, snapshot).force()
        }
    }
  }

  def restoreSnapshot(repositoryName: String, snapshotName: String, indices: String*): JsonResponse = {
    call(createRestoreSnapshotRequest(repositoryName, snapshotName, indices.toList), new JsonResponse(_))
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

  private def createDeleteAllRepositoriesRequest() = {
    new HttpDelete(client.from("/_snapshot/*"))
  }

  private def createGetRepositoriesRequest(repositoriesPatterns: List[String]) = {
    val endpoint = repositoriesPatterns match {
      case Nil => "/_snapshot"
      case list => s"/_snapshot/${stringifyOrAll(list)}"
    }
    new HttpGet(client.from(endpoint))
  }

  private def createGetSnapshotsRequest(repositoryName: String,
                                        snapshotsPatterns: List[String]) = {
    new HttpGet(client.from(s"/_snapshot/$repositoryName/${stringifyOrAll(snapshotsPatterns)}"))
  }

  private def createGetSnapshotStatusesRequest(repositoryName: String,
                                               snapshotsPatterns: List[String]) = {
    new HttpGet(client.from(s"/_snapshot/$repositoryName/${stringifyOrAll(snapshotsPatterns)}/_status"))
  }

  private def createGetAllSnapshotStatusesRequest() = {
    new HttpGet(client.from(s"/_snapshot/_status"))
  }

  private def createNewSnapshotRequest(repositoryName: String,
                                       snapshotName: String,
                                       indices: List[String]) = {
    val request = new HttpPut(client.from(
      s"/_snapshot/$repositoryName/$snapshotName",
      Map("wait_for_completion" -> "true").asJava
    ))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""
         |{
         |  "indices": "${indices.mkString(",")}"
         |}""".stripMargin
    ))
    request
  }

  private def createDeleteSnapshotsOfRequest(repositoryName: String, snapshots: List[String]) = {
    new HttpDelete(client.from(s"/_snapshot/$repositoryName/${stringifyOrWildcard(snapshots)}"))
  }

  private def createRestoreSnapshotRequest(repositoryName: String, snapshotName: String, indices: List[String]) = {
    val request = new HttpPost(client.from(
      s"/_snapshot/$repositoryName/$snapshotName/_restore",
      Map("wait_for_completion" -> "true").asJava
    ))
    request.addHeader("Content-Type", "application/json")
    indices match {
      case Nil =>
        request.setEntity(new StringEntity(
          s"""
             |{
             |  "rename_pattern": "(.+)",
             |  "rename_replacement": "restored_$$1"
             |}""".stripMargin
        ))
      case _ =>
        request.setEntity(new StringEntity(
          s"""
             |{
             |  "indices": "${indices.mkString(",")}",
             |  "rename_pattern": "(.+)",
             |  "rename_replacement": "restored_$$1"
             |}""".stripMargin
        ))
    }
    request

  }

  private def stringifyOrAll(list: List[String]) = stringifyOr(list, "_all")

  private def stringifyOrWildcard(list: List[String]) = stringifyOr(list, "*")

  private def stringifyOr(list: List[String], or: String) = {
    list match {
      case Nil => or
      case all => all.mkString(",")
    }
  }
}

object SnapshotManager {

  class RepositoriesResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val repositories: Map[String, JSON] = responseJson.obj.toMap
  }

  class SnapshotsResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val snapshots: List[JSON] = responseJson.obj("snapshots").arr.toList
  }
}
