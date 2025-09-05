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

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.MediaTypes
import org.apache.pekko.stream.Materializer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.http.Status._
import play.api.http.{HeaderNames, MimeTypes}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.test.FakeRequest
import play.api.test.Helpers.{contentAsJson, defaultAwaitTimeout, status => statusOf}
import uk.gov.hmrc.http.test.ExternalWireMockSupport
import uk.gov.hmrc.ninogateway.models.ErrorResponse

import scala.concurrent.ExecutionContext

class DownstreamConnectorISpec
  extends AnyWordSpec with Matchers with GuiceOneServerPerSuite with ExternalWireMockSupport {

  val sys: ActorSystem = ActorSystem("downstreamConnectorSystem")

  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  implicit val mat: Materializer = Materializer(sys)

  lazy val connector: DownstreamConnector = app.injector.instanceOf[DownstreamConnector]

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure(
        "metrics.enabled" -> false,
        "microservice.services.nino-insights.port" -> externalWireMockPort
      ).build()

  def stubInsightsCheck(body: JsValue)(status: Int, response: JsValue): StubMapping =
    externalWireMockServer.stubFor(
      post(urlEqualTo(s"/check/insights"))
        .withRequestBody(equalToJson(body.toString()))
        .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MediaTypes.`application/json`.value))
        .willReturn(
          aResponse()
            .withBody(response.toString())
            .withStatus(status)
        )
    )

  val reqBody: JsValue = Json.obj("nino" -> "123456")

  "DownstreamConnector" when {
    "request is of type POST" when {

      val req = FakeRequest()
        .withMethod("POST")
        .withBody(reqBody)
        .withHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)

      "downstream service responds with a success response" should {
        "return the success with the JSON payload" in {

          val response = Json.obj(
            "correlationId" -> "220967234589763549876",
            "risk" -> 0,
            "reason" -> "NINO_NOT_ON_WATCHLIST"
          )

          stubInsightsCheck(reqBody)(OK, response)

          val result = connector.forward(req, externalWireMockUrl + "/check/insights")

          statusOf(result) shouldBe OK
          contentAsJson(result) shouldBe response
        }
      }

      "downstream service responds with a non-success response" should {
        "return the failed state" in {

          val response = Json.obj("err" -> "bang")

          stubInsightsCheck(reqBody)(INTERNAL_SERVER_ERROR, response)

          val result = connector.forward(req, externalWireMockUrl + "/check/insights")

          statusOf(result) shouldBe INTERNAL_SERVER_ERROR
          contentAsJson(result) shouldBe response
        }
      }
    }
    "request is another other Http method" should {
      "return Unsupported Method" in {

        val reqBody = Json.obj()
        val req = FakeRequest()
          .withMethod("PUT")
          .withBody(reqBody)
          .withHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)

        val result = connector.forward(req, externalWireMockUrl + "/check/insights")

        statusOf(result) shouldBe METHOD_NOT_ALLOWED
        contentAsJson(result) shouldBe Json.toJson(ErrorResponse(
          "UNSUPPORTED_METHOD",
          "Unsupported HTTP method or content-type"
        ))
      }
    }
  }
}
