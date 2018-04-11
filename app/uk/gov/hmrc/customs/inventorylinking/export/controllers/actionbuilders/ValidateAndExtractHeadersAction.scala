/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.customs.inventorylinking.export.controllers.actionbuilders

import javax.inject.{Inject, Singleton}

import play.api.mvc.{ActionRefiner, _}
import uk.gov.hmrc.customs.inventorylinking.export.controllers.HeaderValidator
import uk.gov.hmrc.customs.inventorylinking.export.logging.ExportsLogger2
import uk.gov.hmrc.customs.inventorylinking.export.model.HeaderConstants._
import uk.gov.hmrc.customs.inventorylinking.export.model.actionbuilders.{CorrelationIdsRequest, ValidatedHeadersRequest}

import scala.concurrent.Future

@Singleton
class ValidateAndExtractHeadersAction @Inject()(validator: HeaderValidator, logger: ExportsLogger2) extends ActionRefiner[CorrelationIdsRequest, ValidatedHeadersRequest] {

  override def refine[A](cr: CorrelationIdsRequest[A]): Future[Either[Result, ValidatedHeadersRequest[A]]] = Future.successful {

    validator.validateHeaders(cr) match {
      case Left(result) => Left(result.XmlResult.withHeaders(XConversationIdHeaderName -> cr.conversationId.value))
      case Right(extracted) =>
        val vhr = ValidatedHeadersRequest(cr.conversationId, cr.correlationId, extracted.maybeBadgeIdentifier, extracted.requestedApiVersion, extracted.clientId, cr.request)
        Right(vhr)
    }
  }

}