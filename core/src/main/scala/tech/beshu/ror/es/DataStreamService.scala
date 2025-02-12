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

import cats.data.NonEmptyList
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, TemplateName}
import tech.beshu.ror.es.DataStreamService.{Capabilities, CreationResult}

trait DataStreamService {

  def checkDataStreamExists(dataStreamName: DataStreamName.Full): Task[Boolean]

  def createIndexLifecyclePolicy(policyName: String, policyJson: ujson.Value): Task[CreationResult]

  def createComponentTemplateForMappings(templateName: TemplateName,
                                         mappingsJson: ujson.Value,
                                         metadata: Map[String, String]): Task[CreationResult]

  def createComponentTemplateForIndex(templateName: TemplateName,
                                      lifecyclePolicyName: String,
                                      metadata: Map[String, String]): Task[CreationResult]

  def createIndexTemplate(templateName: TemplateName,
                          dataStreamName: DataStreamName.Full,
                          componentTemplates: NonEmptyList[TemplateName],
                          metadata: Map[String, String]): Task[CreationResult]

  def createDataStream(dataStreamName: DataStreamName.Full): Task[CreationResult]

  def capabilities: Capabilities = Capabilities.default

}

object DataStreamService {

  final case class Capabilities(ilmMaxPrimaryShardSize: Boolean)
  object Capabilities {
    val default: Capabilities = Capabilities(ilmMaxPrimaryShardSize = true)
  }

  sealed trait CreationResult
  object CreationResult {
    case object Acknowledged extends CreationResult
    case object NotAcknowledged extends CreationResult

    def apply(acknowledged: Boolean): CreationResult = if (acknowledged) {
      Acknowledged
    } else {
      NotAcknowledged
    }
  }
}
