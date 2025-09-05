/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.ninogateway.connectors

import play.api.Logging
import play.api.http.HeaderNames._
import play.api.http.HttpEntity
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Results.{BadGateway, InternalServerError, MethodNotAllowed}
import play.api.mvc.{Request, ResponseHeader, Result}
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}
import uk.gov.hmrc.ninogateway.models.ErrorResponse

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DownstreamConnector @Inject()(httpClient: HttpClientV2) extends Logging {

  def forward(request: Request[JsValue], url: String)(implicit ec: ExecutionContext): Future[Result] =
    request.method match {
      case "POST" =>

        implicit val hc: HeaderCarrier = HeaderCarrier()

        try {
          httpClient
            .post(url"$url")
            .withBody(request.body)
            .setHeader(request.headers.remove(CONTENT_LENGTH, HOST, AUTHORIZATION).headers:_*)
            .execute[HttpResponse]
            .map { response =>
              Result(
                ResponseHeader(response.status, cleanseResponseHeaders(response)),
                HttpEntity.Streamed(response.bodyAsSource, None, response.header(CONTENT_TYPE))
              )
            }.recover { t: Throwable =>
              logger.warn(s"[forward] An exception of type '${t.getClass.getSimpleName}' occurred when the downstream service tried to handle the request")
              BadGateway(Json.toJson(ErrorResponse(
                "REQUEST_DOWNSTREAM",
                "An issue occurred when the downstream service tried to handle the request"
              )))
            }
        } catch {
          case t: Throwable =>
            logger.warn(s"[forward] An exception of type '${t.getClass.getSimpleName}' occurred when forwarding the request to the downstream service")
            Future.successful(InternalServerError(Json.toJson(ErrorResponse(
                "REQUEST_FORWARDING",
                "An issue occurred when forwarding the request to the downstream service"
            ))))
        }

      case _ =>
        logger.info(s"[forward] Client attempted to proxy a '${request.method}' request with content-type '${request.headers(CONTENT_TYPE)}' which is not supported")
        Future.successful(MethodNotAllowed(Json.toJson(ErrorResponse(
          "UNSUPPORTED_METHOD",
          "Unsupported HTTP method or content-type"
        ))))
    }

  private def cleanseResponseHeaders(response: HttpResponse): Map[String, String] =
    response.headers
      .filterNot { case (k, _) =>
        Seq(CONTENT_TYPE, CONTENT_LENGTH).map(_.toUpperCase).contains(k.toUpperCase)
      }
      .view.mapValues(_.mkString).toMap
}
