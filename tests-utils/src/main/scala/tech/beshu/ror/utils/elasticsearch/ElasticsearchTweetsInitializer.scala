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

import tech.beshu.ror.utils.JsonReader.ujsonRead

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneOffset}
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.httpclient.RestClient

object ElasticsearchTweetsInitializer extends ElasticsearchNodeDataInitializer {

  override def initialize(esVersion: String, adminRestClient: RestClient): Unit = {
    val documentManager = new DocumentManager(adminRestClient, esVersion)

    createTweet(documentManager, 1, "cartman", "You can't be the dwarf character, Butters, I'm the dwarf.")
    createPost(documentManager, 2, "morgan", "Let me tell you something my friend. Hope is a dangerous thing. Hope can drive a man insane.")
    createPost(documentManager, 1, "elon", "We're going to Mars!")
    createTweet(documentManager, 3, "bong", "Alright! Check out this bad boy: 12 megabytes of RAM, 500 megabyte hard drive, built-in spreadhseet capabilities and a modem that transmits it over 28,000 bps.")
  }

  private def createTweet(manager: DocumentManager, id: Int, user: String, message: String) = {
    manager.createDoc("twitter", "tweet", id, jsonFrom(user, message)).force()
  }

  private def createPost(manager: DocumentManager, id: Int, user: String, message: String) = {
    manager.createDoc("facebook", "post", id, jsonFrom(user, message)).force()
  }

  private def jsonFrom(user: String, content: String) = {
    ujsonRead(
      s"""{
         |  "@timestamp": "${LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)}",
         |  "user": "$user",
         |  "post_date": "${LocalDateTime.now().format(DateTimeFormatter.BASIC_ISO_DATE)}",
         |  "message": "$content"
         |}""".stripMargin)
  }
}
