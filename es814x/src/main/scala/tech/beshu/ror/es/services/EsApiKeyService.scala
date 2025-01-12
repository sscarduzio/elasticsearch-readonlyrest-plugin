package tech.beshu.ror.es.services

import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.CancelablePromise
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.domain.ApiKey
import tech.beshu.ror.utils.ActionListenerToTaskAdapter

import java.util.Base64
import scala.concurrent.Promise

final case class ApiKeyDefinition(id: NonEmptyString, name: NonEmptyString)

class EsApiKeyService(underlying: AnyRef) {

  def getApiKeys(): Task[Vector[ApiKey]] = {
    Task.now(())
  }

  def getApiKeyDefinitionOf(apiKey: ApiKey): Task[Option[ApiKeyDefinition]] = {
    val id = getIdFrom(apiKey)
    getApiKeys(id)
      .map { response =>
        response.apiKeyInfos match
          case Nil => None
          case one :: Nil => Some(one)
          case one :: rest =>
            // todo: warning
            Some(one)
      }
  }

  def authenticateUsing(apiKey: ApiKey): Task[Boolean] = {
    val threadContext = createThreadContext(apiKey)
    val credentials = getCredentialsFromHeader(threadContext)
    tryAuthenticate(threadContext, credentials)
      .map(_.isAuthenticated)
  }

  private def getIdFrom(apiKey: ApiKey): String = {
    val threadContext = createThreadContext(apiKey)
    val credentials = getCredentialsFromHeader(threadContext)
    credentials.id
  }

  private def getApiKeys(id: String) = {
    val listener = new ActionListenerToTaskAdapter[AnyRef]
    on(underlying).call("getApiKeys", null, null, null, id, listener)
    listener
      .result
      .map(new GetApiKeyResponse(_))
  }

  private def tryAuthenticate(threadContext: ThreadContext,
                              credentials: ApiKeyCredentials) = {
    val listener = new ActionListenerToTaskAdapter[AnyRef]
    on(underlying).call("tryAuthenticate", threadContext, credentials.underlying, listener)
    listener
      .result
      .map(new AuthenticationResult(_))
  }

  private def createThreadContext(apiKey: ApiKey) = {
    val threadContext = new ThreadContext(Settings.EMPTY)
    threadContext.putHeader("Authorization", s"ApiKey ${apiKey.value.value}")
    threadContext
  }

  private def getCredentialsFromHeader(threadContext: ThreadContext) = {
    new ApiKeyCredentials {
      on(underlying).call("getCredentialsFromHeader", threadContext).get[AnyRef]()
    }
  }

  private class ApiKeyCredentials(val underlying: AnyRef) {
    lazy val id: String = on(underlying).call("getId").get[String]()
  }

  private class AuthenticationResult(val underlying: AnyRef) {
    lazy val isAuthenticated: Boolean = on(underlying).call("isAuthenticated").get[Boolean]()
  }

  private class GetApiKeyResponse(val underlying: AnyRef) {
    lazy val apiKeyInfos: List[EsApiKey] =
      on(underlying)
        .call("getApiKeyInfos")
        .get[Array[AnyRef]]()
        .map(new EsApiKey(_))
  }

  private class EsApiKey(val underlying: AnyRef) {
    lazy val id: Boolean = on(underlying).call("getId").get[String]()
    lazy val name: Boolean = on(underlying).call("getName").get[String]()
    lazy val invalidated: Boolean = on(underlying).call("isInvalidated").get[Boolean]()
  }
}
