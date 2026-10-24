package com.fiap.mechanicalhub.billing.infrastructure.mercadopago

import com.fiap.mechanicalhub.billing.application.payment.CreateOrderCommand
import com.fiap.mechanicalhub.billing.application.payment.PermanentGatewayException
import com.fiap.mechanicalhub.billing.application.payment.TransientGatewayException
import com.fiap.mechanicalhub.billing.domain.payment.CancelOutcome
import com.fiap.mechanicalhub.billing.domain.payment.OrderLookup
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MercadoPagoPaymentGatewayTest {
    private lateinit var server: WireMockServer
    private lateinit var gateway: MercadoPagoPaymentGateway

    private val command =
        CreateOrderCommand(
            orderId = "ord-1",
            amount = BigDecimal("350"),
            expiresIn = Duration.ofHours(24).plusMinutes(5),
            idempotencyKey = "tx:GENERATE_PAYMENT_LINK",
        )

    @BeforeEach
    fun setUp() {
        server = WireMockServer(options().dynamicPort())
        server.start()
        gateway = MercadoPagoPaymentGateway(MercadoPagoProperties("http://localhost:${server.port()}", "tok"))
    }

    @AfterEach
    fun tearDown() = server.stop()

    private fun stubGet(url: String, status: Int, body: String = "{}") =
        server.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(status).withBody(body)))

    private fun stubPost(url: String, status: Int, body: String = "{}") =
        server.stubFor(post(urlEqualTo(url)).willReturn(aResponse().withStatus(status).withBody(body)))

    @Test
    fun `given sucesso when criar order then envia payload e idempotency key e devolve checkout`() {
        stubPost("/v1/orders", 201, """{"id":"ORD1","status":"created","checkout_url":"https://mp/checkout"}""")

        val created = gateway.createOrder(command)

        assertEquals("ORD1", created.mpOrderId)
        assertEquals("https://mp/checkout", created.checkoutUrl)
        server.verify(
            postRequestedFor(urlEqualTo("/v1/orders"))
                .withHeader("X-Idempotency-Key", equalTo("tx:GENERATE_PAYMENT_LINK"))
                .withHeader("Authorization", equalTo("Bearer tok"))
                .withRequestBody(
                    equalToJson(
                        """
                        {"type":"online","processing_mode":"manual","total_amount":"350.00",
                         "external_reference":"ord-1","expiration_time":"PT24H5M",
                         "items":[{"unit_price":"350.00","quantity":1}],
                         "config":{"payment_method":{"not_allowed_types":["ticket"]}}}
                        """.trimIndent(),
                        true,
                        true,
                    ),
                ),
        )
    }

    @Test
    fun `given 5xx ou 429 when criar order then transitorio`() {
        listOf(500, 503, 429).forEach { status ->
            stubPost("/v1/orders", status)

            assertFailsWith<TransientGatewayException> { gateway.createOrder(command) }
        }
    }

    @Test
    fun `given 4xx de cliente when criar order then permanente`() {
        listOf(400, 401, 403, 404).forEach { status ->
            stubPost("/v1/orders", status)

            assertFailsWith<PermanentGatewayException> { gateway.createOrder(command) }
        }
    }

    @Test
    fun `given resposta sem checkout_url when criar order then permanente`() {
        stubPost("/v1/orders", 201, """{"id":"ORD1"}""")

        assertFailsWith<PermanentGatewayException> { gateway.createOrder(command) }
    }

    @Test
    fun `given servidor fora when criar order then transitorio`() {
        server.stop()

        assertFailsWith<TransientGatewayException> { gateway.createOrder(command) }
    }

    @Test
    fun `given order processada when consultar then Found`() {
        stubGet("/v1/orders/ORD1", 200, """{"status":"processed","status_detail":"accredited"}""")

        val lookup = gateway.getOrder("ORD1")

        assertEquals(OrderLookup.Found("processed", "accredited"), lookup)
    }

    @Test
    fun `given 5xx when consultar then transitorio`() {
        stubGet("/v1/orders/ORD1", 500)

        assertEquals(OrderLookup.Transient, gateway.getOrder("ORD1"))
    }

    @Test
    fun `given 404 when consultar then permanente`() {
        stubGet("/v1/orders/ORD1", 404)

        assertEquals(OrderLookup.Permanent, gateway.getOrder("ORD1"))
    }

    @Test
    fun `given corpo invalido when consultar then permanente`() {
        stubGet("/v1/orders/ORD1", 200, "nao e json")

        assertEquals(OrderLookup.Permanent, gateway.getOrder("ORD1"))
    }

    @Test
    fun `given servidor fora when consultar then transitorio`() {
        server.stop()

        assertEquals(OrderLookup.Transient, gateway.getOrder("ORD1"))
    }

    @Test
    fun `given respostas when cancelar then mapeia para CancelOutcome`() {
        val url = "/v1/orders/ORD1/cancel"
        val expected =
            mapOf(
                200 to CancelOutcome.Canceled,
                409 to CancelOutcome.Conflict,
                429 to CancelOutcome.Transient,
                502 to CancelOutcome.Transient,
                401 to CancelOutcome.Permanent,
                404 to CancelOutcome.Permanent,
            )

        expected.forEach { (status, outcome) ->
            stubPost(url, status)

            assertEquals(outcome, gateway.cancelOrder("ORD1", "tx:CANCEL_MP_ORDER"))
        }
        server.verify(postRequestedFor(urlEqualTo(url)).withHeader("X-Idempotency-Key", equalTo("tx:CANCEL_MP_ORDER")))
    }

    @Test
    fun `given servidor fora when cancelar then transitorio`() {
        server.stop()

        assertEquals(CancelOutcome.Transient, gateway.cancelOrder("ORD1", "tx:CANCEL_MP_ORDER"))
    }
}
