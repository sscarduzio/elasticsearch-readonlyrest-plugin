/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.indices.analyze.AnalyzeAction.{AnalyzeToken => AdminAnalyzeToken, AnalyzeTokenList => AdminAnalyzeTokenList, CharFilteredText => AdminCharFilteredText, DetailAnalyzeResponse => AdminDetailAnalyzeResponse, Request => AdminAnalyzeRequest, Response => AdminAnalyzeResponse}
import org.elasticsearch.client.indices.AnalyzeResponse.{AnalyzeToken => ClientAnalyzeToken}
import org.elasticsearch.client.indices.DetailAnalyzeResponse.{AnalyzeTokenList => ClientAnalyzeTokenList, CharFilteredText => ClientCharFilteredText}
import org.elasticsearch.client.indices.{AnalyzeRequest => ClientAnalyzeRequest, AnalyzeResponse => ClientAnalyzeResponse, DetailAnalyzeResponse => ClientDetailAnalyzeResponse}
import org.joor.Reflect.on

import scala.collection.JavaConverters._

object Analyze {

  implicit class AnalyzeRequestOps(val request: AdminAnalyzeRequest) extends AnyVal {
    def toAnalyzeRequest: ClientAnalyzeRequest = {
      Option(request.analyzer()) match {
        case Some(analyzer) =>
          ClientAnalyzeRequest
            .buildCustomAnalyzer(request.index(), analyzer)
            .build(request.text(): _*)
        case None =>
          val builder = ClientAnalyzeRequest.buildCustomNormalizer(request.index())
          request
            .charFilters().asScala
            .foldLeft(builder) { case (acc, elem) =>
              Option(elem.definition) match {
                case Some(definition) =>
                  val map = on(definition).call("getAsStructuredMap").get[java.util.Map[String, Object]]()
                  acc.addCharFilter(map)
                case None =>
                  acc.addCharFilter(elem.name)
              }
            }
          request
            .tokenFilters().asScala
            .foldLeft(builder) { case (acc, elem) =>
              Option(elem.definition) match {
                case Some(definition) =>
                  val map = on(definition).call("getAsStructuredMap").get[java.util.Map[String, Object]]()
                  acc.addCharFilter(map)
                case None =>
                  acc.addCharFilter(elem.name)
              }
            }
          builder.build(request.text(): _*)
      }
    }
  }

  implicit class AnalyzeResponseOps(val response: ClientAnalyzeResponse) extends AnyVal {
    def toAnalyzeResponse: AdminAnalyzeResponse = {
      new AdminAnalyzeResponse(
        response.getTokens.asScala
          .map(toAnalyzeActionToken)
          .asJava,
        Option(response.detail()).map(toDetailAnalyzeResponse).orNull
      )
    }
  }

  private def toDetailAnalyzeResponse(detail: ClientDetailAnalyzeResponse): AdminDetailAnalyzeResponse = {
    Option(detail.charfilters()) match {
      case Some(charFilters) =>
        new AdminDetailAnalyzeResponse(
          charFilters.map(toAnalyzeCharFilter),
          toAnalyzeTokenList(detail.tokenizer()),
          detail.tokenfilters().map(toAnalyzeTokenList)
        )
      case None =>
        new AdminDetailAnalyzeResponse(
          toAnalyzeTokenList(detail.analyzer())
        )
    }
  }

  private def toAnalyzeTokenList(analyzeTokenList: ClientAnalyzeTokenList): AdminAnalyzeTokenList = {
    new AdminAnalyzeTokenList(
      analyzeTokenList.getName,
      analyzeTokenList.getTokens.map(toAnalyzeActionToken)
    )
  }

  private def toAnalyzeActionToken(token: ClientAnalyzeToken): AdminAnalyzeToken = {
    new AdminAnalyzeToken(
      token.getTerm,
      token.getPosition,
      token.getStartOffset,
      token.getEndOffset,
      token.getPositionLength,
      token.getType,
      token.getAttributes
    )
  }

  private def toAnalyzeCharFilter(charFiler: ClientCharFilteredText): AdminCharFilteredText = {
    new AdminCharFilteredText(charFiler.getName, charFiler.getTexts)
  }
}
