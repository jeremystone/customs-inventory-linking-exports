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
import play.api.http.Status
import play.api.http.Status.UNAUTHORIZED
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.ErrorInternalServerError
import play.api.mvc.{ActionRefiner, RequestHeader, Result}
import uk.gov.hmrc.auth.core.AuthProvider.{GovernmentGateway, PrivilegedApplication}
import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.retrieve.Retrievals
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.{UnauthorizedCode, errorBadRequest}
import uk.gov.hmrc.customs.inventorylinking.export.controllers.CustomHeaderNames
import uk.gov.hmrc.customs.inventorylinking.export.logging.ExportsLogger
import uk.gov.hmrc.customs.inventorylinking.export.model.actionbuilders.ActionBuilderModelHelper._
import uk.gov.hmrc.customs.inventorylinking.export.model.actionbuilders.{AuthorisedRequest, ValidatedHeadersRequest}
import uk.gov.hmrc.customs.inventorylinking.export.model.{BadgeIdentifier, Eori}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.logging.Authorization
import uk.gov.hmrc.play.HeaderCarrierConverter

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Left
import scala.util.control.NonFatal

@Singleton
class AuthAction @Inject()(
                            override val authConnector: AuthConnector,
                            logger: ExportsLogger
                          ) extends ActionRefiner[ValidatedHeadersRequest, AuthorisedRequest] with AuthorisedFunctions {
  protected type EitherResultOrAuthRequest[A] = Either[Result, AuthorisedRequest[A]]

  protected val errorResponseUnauthorisedGeneral =
    ErrorResponse(Status.UNAUTHORIZED, UnauthorizedCode, "Unauthorised request")
  private val errorResponseBadgeIdentifierHeaderMissing = errorBadRequest(s"${CustomHeaderNames.XBadgeIdentifierHeaderName} header is missing or invalid")
  private lazy val errorResponseEoriNotFoundInCustomsEnrolment =
    ErrorResponse(UNAUTHORIZED, UnauthorizedCode, "EORI number not found in Customs Enrolment")
  private lazy val xBadgeIdentifierRegex = "^[0-9A-Z]{6,12}$".r

  override def refine[A](vhr: ValidatedHeadersRequest[A]): Future[Either[Result, AuthorisedRequest[A]]] = {
    implicit val implicitVhr: ValidatedHeadersRequest[A] = vhr

    authoriseAsCsp.flatMap{
      case Right(maybeAuthorisedAsCspWithBadgeId) =>
        maybeAuthorisedAsCspWithBadgeId.fold{
          authoriseAsNonCsp
        }{ badgeId =>
          Future.successful(Right(vhr.toCspAuthorisedRequest(badgeId)))
        }
      case Left(result) =>
        Future.successful(Left(result))
    }
  }

  // pure function that tames exceptions throw by HMRC auth api into an Either
  // this enables calling function to not worry about recover blocks
  // returns a Future of Left(Result) on error or a Right(AuthorisedRequest) on success
  private def authoriseAsCsp[A](implicit vhr: ValidatedHeadersRequest[A]): Future[Either[Result, Option[BadgeIdentifier]]] = {
    implicit def hc(implicit rh: RequestHeader): HeaderCarrier = HeaderCarrierConverter.fromHeadersAndSession(rh.headers)

    authorised(Enrolment("write:customs-inventory-linking-exports") and AuthProviders(PrivilegedApplication)) {
      Future.successful{
        maybeBadgeIdentifierWithValidation.fold[Either[Result, Option[BadgeIdentifier]]]{
          logger.error("badge identifier invalid or not present for CSP")
          Left(errorResponseBadgeIdentifierHeaderMissing.XmlResult.withConversationId)
        }{ badgeId =>
          logger.debug("Authorising as CSP")
          Right(Some(badgeId))
        }
      }
    }.recover{
      case NonFatal(_: AuthorisationException) =>
        logger.error("Not authorised as CSP")
        Right(None)
      case NonFatal(e) =>
        logger.error("Error authorising CSP", e)
        Left(ErrorInternalServerError.XmlResult.withConversationId)
    }
  }

  private def maybeBadgeIdentifierWithValidation[A](implicit vhr: ValidatedHeadersRequest[A]): Option[BadgeIdentifier] = {
    val maybeBadgeId: Option[String] = vhr.request.headers.toSimpleMap.get(CustomHeaderNames.XBadgeIdentifierHeaderName)
    maybeBadgeId.filter(xBadgeIdentifierRegex.findFirstIn(_).nonEmpty).map(BadgeIdentifier)
  }


  // pure function that tames exceptions throw by HMRC auth api into an Either
  // this enables calling function to not worry about recover blocks
  // returns a Future of Left(Result) on error or a Right(AuthorisedRequest) on success
  private def authoriseAsNonCsp[A](implicit vhr: ValidatedHeadersRequest[A]): Future[Either[Result, AuthorisedRequest[A]]] = {
    implicit def hc(implicit rh: RequestHeader): HeaderCarrier = HeaderCarrierConverter.fromHeadersAndSession(rh.headers)

    authorised(Enrolment("HMRC-CUS-ORG") and AuthProviders(GovernmentGateway)).retrieve(Retrievals.authorisedEnrolments) {
      enrolments =>
        val maybeEori: Option[Eori] = findEoriInCustomsEnrolment(enrolments, hc.authorization)
        logger.debug(s"EORI from Customs enrolment for non-CSP request: $maybeEori")
        maybeEori.fold[Future[Either[Result, AuthorisedRequest[A]]]]{
          Future.successful(Left(errorResponseEoriNotFoundInCustomsEnrolment.XmlResult.withConversationId))
        }{ eori =>
          logger.debug("Authorising as non-CSP")
          Future.successful(Right(vhr.toNonCspAuthorisedRequest(eori)))
        }
    }.recover{
      case NonFatal(_: AuthorisationException) =>
        Left(errorResponseUnauthorisedGeneral.XmlResult.withConversationId)
      case NonFatal(e) =>
        logger.error("Error authorising Non CSP", e)
        Left(ErrorInternalServerError.XmlResult.withConversationId)
    }
  }

  private def findEoriInCustomsEnrolment[A](enrolments: Enrolments, authHeader: Option[Authorization])(implicit vhr: ValidatedHeadersRequest[A], hc: HeaderCarrier): Option[Eori] = {
    val maybeCustomsEnrolment = enrolments.getEnrolment("HMRC-CUS-ORG")
    if (maybeCustomsEnrolment.isEmpty) {
      logger.warn(s"Customs enrolment HMRC-CUS-ORG not retrieved for authorised non-CSP call")
    }
    for {
      customsEnrolment <- maybeCustomsEnrolment
      eoriIdentifier <- customsEnrolment.getIdentifier("EORINumber")
    } yield Eori(eoriIdentifier.value)
  }

}