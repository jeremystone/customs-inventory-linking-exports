/*
 * Copyright 2020 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.customs.inventorylinking.export.connectors

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

import com.google.inject._
import org.joda.time.DateTime
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE, DATE, X_FORWARDED_HOST}
import play.api.http.MimeTypes
import uk.gov.hmrc.circuitbreaker.{CircuitBreakerConfig, UsingCircuitBreaker}
import uk.gov.hmrc.customs.api.common.config.ServiceConfigProvider
import uk.gov.hmrc.customs.inventorylinking.export.logging.ExportsLogger
import uk.gov.hmrc.customs.inventorylinking.export.model.actionbuilders.{HasConversationId, ValidatedPayloadRequest}
import uk.gov.hmrc.customs.inventorylinking.export.services.ExportsConfigService
import uk.gov.hmrc.http._
import uk.gov.hmrc.http.logging.Authorization
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{NodeSeq, PrettyPrinter, TopScope}

@Singleton
class ExportsConnector @Inject() (http: HttpClient,
                                  logger: ExportsLogger,
                                  serviceConfigProvider: ServiceConfigProvider,
                                  config: ExportsConfigService)
                                 (implicit ec: ExecutionContext) extends UsingCircuitBreaker {

  private val configKey = "mdg-exports"

  def send[A](xml: NodeSeq, date: DateTime, correlationId: UUID)(implicit vpr: ValidatedPayloadRequest[A], hc: HeaderCarrier): Future[HttpResponse] = {
    val config = Option(serviceConfigProvider.getConfig(s"${vpr.requestedApiVersion.configPrefix}$configKey")).getOrElse(throw new IllegalArgumentException("config not found"))
    val bearerToken = "Bearer " + config.bearerToken.getOrElse(throw new IllegalStateException("no bearer token was found in config"))
    implicit val headerCarrier: HeaderCarrier = hc.copy(extraHeaders = hc.extraHeaders ++ getHeaders(date, correlationId), authorization = Some(Authorization(bearerToken)))
    val startTime = LocalDateTime.now
    withCircuitBreaker(post(xml, config.url)(vpr, headerCarrier))(headerCarrier).map{
      response => {
        logCallDuration(startTime)
        logger.debug(s"Response status ${response.status} and response body ${formatResponseBody(response.body)}")
      }
        response
    }
  }

  private def getHeaders(date: DateTime, correlationId: UUID) = {
    Seq(
      (ACCEPT, MimeTypes.XML),
      (CONTENT_TYPE, MimeTypes.XML + "; charset=UTF-8"),
      (DATE, date.toString("EEE, dd MMM yyyy HH:mm:ss z")),
      (X_FORWARDED_HOST, "MDTP"),
      ("X-Correlation-ID", correlationId.toString))
  }

  private def post[A](xml: NodeSeq, url: String)(implicit vpr: ValidatedPayloadRequest[A], hc: HeaderCarrier) = {
    logger.debug(s"Posting inventory linking exports.\nurl = $url\npayload = \n${xml.toString}")

    http.POSTString[HttpResponse](url, xml.toString())
      .recoverWith {
        case httpError: HttpException => Future.failed(new RuntimeException(httpError))
        case e: Throwable =>
          logger.error(s"Call to inventory linking exports failed. url = $url", e)
          Future.failed(e)
      }
  }

  protected def logCallDuration(startTime: LocalDateTime)(implicit r: HasConversationId): Unit ={
    val callDuration = ChronoUnit.MILLIS.between(startTime, LocalDateTime.now)
    logger.info(s"Outbound call duration was $callDuration ms")
  }

  private def formatResponseBody(responseBody: String) = {
    if (responseBody.isEmpty) {
      "<empty>"
    } else {
      new PrettyPrinter(120, 2).format(xml.XML.loadString(responseBody), TopScope)
    }
  }

  override protected def circuitBreakerConfig: CircuitBreakerConfig =
    CircuitBreakerConfig(
      serviceName = "customs-inventory-linking-exports",
      numberOfCallsToTriggerStateChange = config.exportsCircuitBreakerConfig.numberOfCallsToTriggerStateChange,
      unavailablePeriodDuration = config.exportsCircuitBreakerConfig.unavailablePeriodDurationInMillis,
      unstablePeriodDuration = config.exportsCircuitBreakerConfig.unstablePeriodDurationInMillis
    )

  override protected def breakOnException(t: Throwable): Boolean = t match {
    case _: BadRequestException | _: NotFoundException | _: Upstream4xxResponse => false
    case _ => true
  }

}
