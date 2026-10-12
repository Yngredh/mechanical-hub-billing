package com.fiap.mechanicalhub.billing.infrastructure.web

import com.fiap.mechanicalhub.billing.TestcontainersConfiguration
import com.fiap.mechanicalhub.billing.application.payment.CreateOrderCommand
import com.fiap.mechanicalhub.billing.application.payment.CreatedOrder
import com.fiap.mechanicalhub.billing.application.payment.PaymentGateway
import com.fiap.mechanicalhub.billing.domain.payment.CancelOutcome
import com.fiap.mechanicalhub.billing.domain.payment.OrderLookup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals

class FakeGateway : PaymentGateway {
    @Volatile
    var lookup: OrderLookup = OrderLookup.Found("processed", "accredited")

    override fun createOrder(command: CreateOrderCommand): CreatedOrder = error("not used")

    override fun getOrder(mpOrderId: String): OrderLookup = lookup

    override fun cancelOrder(mpOrderId: String, idempotencyKey: String): CancelOutcome = error("not used")
}

@TestConfiguration(proxyBeanMethods = false)
class FakeGatewayConfig {
    @Bean
    @Primary
    fun fakeGateway() = FakeGateway()
}

@Import(TestcontainersConfiguration::class, FakeGatewayConfig::class)
@SpringBootTest(properties = ["mercado-pago.webhook-secret=test-secret"])
@AutoConfigureMockMvc
class PaymentWebhookIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var gateway: FakeGateway

    private lateinit var mpOrderId: String

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM outbox_events")
        gateway.lookup = OrderLookup.Found("processed", "accredited")
        mpOrderId = "ORD${UUID.randomUUID().toString().replace("-", "")}"
        insertPendingPayment(mpOrderId)
    }

    @Test
    fun `given invalid signature when posting webhook then 401`() {
        mockMvc
            .perform(
                post("/webhooks/payment")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-signature", "ts=1,v1=deadbeef")
                    .header("x-request-id", "req-1")
                    .content(body(mpOrderId)),
            ).andExpect(status().isUnauthorized)
        assertEquals("PENDING", paymentStatus())
    }

    @Test
    fun `given paid order when posting webhook then approves once and writes one outbox event`() {
        postSigned(mpOrderId).andExpect(status().isOk)
        postSigned(mpOrderId).andExpect(status().isOk)

        assertEquals("APPROVED", paymentStatus())
        assertEquals(1, outboxCount("paymentConfirmed"))
    }

    @Test
    fun `given order not paid when posting webhook then 200 without effect`() {
        gateway.lookup = OrderLookup.Found("action_required", "waiting_payment")

        postSigned(mpOrderId).andExpect(status().isOk)

        assertEquals("PENDING", paymentStatus())
        assertEquals(0, outboxCount("paymentConfirmed"))
    }

    @Test
    fun `given transient lookup failure when posting webhook then 500`() {
        gateway.lookup = OrderLookup.Transient

        postSigned(mpOrderId).andExpect(status().isInternalServerError)
    }

    @Test
    fun `given no gateway headers when calling another route then 401`() {
        mockMvc.perform(post("/payments")).andExpect(status().isUnauthorized)
    }

    private fun postSigned(dataId: String) =
        mockMvc.perform(
            post("/webhooks/payment")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-signature", signatureFor(dataId, "req-1", "1700000000"))
                .header("x-request-id", "req-1")
                .content(body(dataId)),
        )

    private fun body(dataId: String) = """{"type":"order","data":{"id":"$dataId"}}"""

    private fun signatureFor(dataId: String, requestId: String, ts: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("test-secret".toByteArray(), "HmacSHA256"))
        val manifest = "id:${dataId.lowercase()};request-id:$requestId;ts:$ts;"
        val hex = mac.doFinal(manifest.toByteArray()).joinToString("") { "%02x".format(it) }
        return "ts=$ts,v1=$hex"
    }

    private fun paymentStatus() =
        jdbc.queryForObject("SELECT status FROM payments WHERE mp_order_id = ?", String::class.java, mpOrderId)

    private fun outboxCount(type: String) =
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Int::class.java, type)

    private fun insertPendingPayment(mpOrder: String) {
        val budgetId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO budgets (budget_id, order_id, transaction_id, labor_total, material_total, total) " +
                "VALUES (?, ?, ?, 100, 50, 150)",
            budgetId,
            orderId,
            transactionId,
        )
        jdbc.update(
            "INSERT INTO payments (payment_id, order_id, transaction_id, budget_id, mp_order_id, checkout_url, " +
                "amount, status, due_at, expires_at) " +
                "VALUES (?, ?, ?, ?, ?, 'http://checkout', 150, 'PENDING', now(), now())",
            UUID.randomUUID(),
            orderId,
            transactionId,
            budgetId,
            mpOrder,
        )
    }
}
