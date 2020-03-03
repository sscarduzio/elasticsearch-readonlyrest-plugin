package tech.beshu.ror.utils.elasticsearch

import java.time.Duration
import java.util.function.BiPredicate

import net.jodah.failsafe.{Failsafe, RetryPolicy}
import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPut}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.JsonResponse
import tech.beshu.ror.utils.httpclient.RestClient

class TemplateManager(client: RestClient)
  extends BaseManager(client) {

  def getTemplate(name: String): JsonResponse =
    call(createGetTemplateRequest(name), new JsonResponse(_))

  def getTemplates: JsonResponse =
    call(createGetTemplatesRequest, new JsonResponse(_))

  def insertTemplate(name: String, templateContent: String): JsonResponse =
    call(createInsertTemplateRequest(name, templateContent), new JsonResponse(_))

  def insertTemplateAndWaitForIndexing(name: String, templateContent: String): Unit = {
    val result = insertTemplate(name, templateContent)
    if (!result.isSuccess) throw new IllegalStateException("Cannot insert template: [" + result.responseCode + "]\nResponse: " + result.body)
    val retryPolicy = new RetryPolicy[Boolean]()
      .handleIf(isNotIndexedYet)
      .withMaxRetries(20)
      .withDelay(Duration.ofMillis(200))
    Failsafe
      .`with`[Boolean, RetryPolicy[Boolean]](retryPolicy)
      .get(() => isTemplateIndexed(name))
  }

  def deleteTemplate(name: String): JsonResponse =
    call(createDeleteTemplateRequest(name), new JsonResponse(_))

  private def createGetTemplateRequest(name: String) = {
    val request = new HttpGet(client.from("/_template/" + name))
    request.setHeader("timeout", "50s")
    request
  }

  private def createGetTemplatesRequest = {
    val request = new HttpGet(client.from("/_template"))
    request.setHeader("timeout", "50s")
    request
  }

  private def createDeleteTemplateRequest(templateName: String) = {
    val request = new HttpDelete(client.from("/_template/" + templateName))
    request.setHeader("timeout", "50s")
    request
  }

  private def createInsertTemplateRequest(name: String, templateContent: String) = try {
    val request = new HttpPut(client.from("/_template/" + name))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(templateContent))
    request
  } catch {
    case ex: Exception =>
      throw new IllegalStateException("Cannot insert document", ex)
  }

  private def isTemplateIndexed(templateName: String) =
    getTemplates.responseJson.obj.contains(templateName)

  private def isNotIndexedYet: BiPredicate[Boolean, Throwable] =
    (indexed: Boolean, throwable: Throwable) => throwable != null || !indexed
}
