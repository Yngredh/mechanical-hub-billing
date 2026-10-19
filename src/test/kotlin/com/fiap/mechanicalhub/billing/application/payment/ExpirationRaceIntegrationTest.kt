package com.fiap.mechanicalhub.billing.application.payment

import com.fiap.mechanicalhub.billing.TestcontainersConfiguration
import com.fiap.mechanicalhub.billing.domain.payment.CancelOutcome
import com.fiap.mechanicalhub.billing.domain.payment.OrderLookup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.assertEquals

class ScriptedGateway : PaymentGateway {
    @Volatile var cancel: CancelOutcome = CancelOutcome.Canceled

    @Volatile var lookup: OrderLookup = OrderLookup.Found("processed", "accredited")

    override fun createOrder(command: CreateOrderCommand): CreatedOrder = error("not used")

    override fun getOrder(mpOrderId: String): OrderLookup = lookup

    override fun cancelOrder(mpOrderId: String, idempotencyKey: String): CancelOutcome = cancel
}

@TestConfiguration(proxyBeanMethods = false)
class ScriptedGatewayConfig {
    @Bean
    @Primary
    fun scriptedGateway() = ScriptedGateway()
}

@Import(TestcontainersConfiguration::class, ScriptedGatewayConfig::class)
@SpringBootTest(properties = ["mercado-pago.webhook-secret=test-secret"])
class ExpirationRaceIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var gateway: ScriptedGateway

    @Autowired private lateinit var expire: ExpirePaymentsUseCase

    @Autowired private lateinit var confirm: ConfirmPaymentUseCase

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM outbox_events")
        jdbc.update("DELETE FROM payments")
        jdbc.update("DELETE FROM budgets")
        gateway.cancel = CancelOutcome.Canceled
        gateway.lookup = OrderLookup.Found("processed", "accredited")
    }

    private fun insertDuePayment(mpOrder: String): UUID {
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
                "VALUES (?, ?, ?, ?, ?, 'http://checkout', 150, 'PENDING', now() - interval '1 hour', now())",
            UUID.randomUUID(),
            orderId,
            transactionId,
            budgetId,
            mpOrder,
        )
        return orderId
    }

    private fun events(): List<String?> =
        jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY sequence", String::class.java)

    private fun status(mpOrder: String) =
        jdbc.queryForObject("SELECT status FROM payments WHERE mp_order_id = ?", String::class.java, mpOrder)

    private fun reason() =
        jdbc.queryForObject("SELECT payload->>'reason' FROM outbox_events WHERE event_type = 'paymentFailed'", String::class.java)

    @Test
    fun `given cancel accepted when running job then cancels payment and publishes paymentFailed EXPIRED`() {
        insertDuePayment("ORD-A")

        val summary = expire.execute()

        assertEquals(1, summary.failed)
        assertEquals("CANCELED", status("ORD-A"))
        assertEquals(listOf("paymentFailed"), events())
        assertEquals("EXPIRED", reason())
    }

    @Test
    fun `given conflict and order canceled when running job then publishes paymentFailed EXPIRED`() {
        insertDuePayment("ORD-B1")
        gateway.cancel = CancelOutcome.Conflict
        gateway.lookup = OrderLookup.Found("canceled", null)

        expire.execute()

        assertEquals("CANCELED", status("ORD-B1"))
        assertEquals("EXPIRED", reason())
    }

    @Test
    fun `given conflict and order paid when running job then approves and publishes paymentConfirmed`() {
        insertDuePayment("ORD-B2")
        gateway.cancel = CancelOutcome.Conflict

        val summary = expire.execute()

        assertEquals(1, summary.confirmed)
        assertEquals("APPROVED", status("ORD-B2"))
        assertEquals(listOf("paymentConfirmed"), events())
    }

    @Test
    fun `given conflict and order failed when running job then publishes paymentFailed FAILED`() {
        insertDuePayment("ORD-B3")
        gateway.cancel = CancelOutcome.Conflict
        gateway.lookup = OrderLookup.Found("failed", null)

        expire.execute()

        assertEquals("CANCELED", status("ORD-B3"))
        assertEquals("FAILED", reason())
    }

    @Test
    fun `given conflict and order still open when running job then waits for next cycle`() {
        insertDuePayment("ORD-B4")
        gateway.cancel = CancelOutcome.Conflict
        gateway.lookup = OrderLookup.Found("action_required", "waiting_payment")

        val summary = expire.execute()

        assertEquals(1, summary.waiting)
        assertEquals("PENDING", status("ORD-B4"))
        assertEquals(emptyList(), events())
    }

    @Test
    fun `given transient cancel failure when running job then leaves payment pending`() {
        insertDuePayment("ORD-T")
        gateway.cancel = CancelOutcome.Transient

        val summary = expire.execute()

        assertEquals(1, summary.waiting)
        assertEquals("PENDING", status("ORD-T"))
        assertEquals(emptyList(), events())
    }

    @Test
    fun `given payment approved by webhook when job cancel is accepted then emits only paymentConfirmed`() {
        insertDuePayment("ORD-W")
        confirm.execute("ORD-W")

        expire.execute()

        assertEquals("APPROVED", status("ORD-W"))
        assertEquals(listOf("paymentConfirmed"), events())
    }

    @Test
    fun `given job and webhook running concurrently when order is paid then emits a single paymentConfirmed per payment`() {
        gateway.cancel = CancelOutcome.Conflict
        val orders = (1..RACES).map { "ORD-R$it" }
        orders.forEach { insertDuePayment(it) }
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)

        val job =
            pool.submit {
                barrier.await()
                expire.execute()
            }
        val webhooks =
            pool.submit {
                barrier.await()
                orders.forEach { confirm.execute(it) }
            }
        job.get()
        webhooks.get()
        pool.shutdown()

        assertEquals(RACES, events().size)
        assertEquals(setOf("paymentConfirmed"), events().toSet())
        assertEquals(setOf("APPROVED"), orders.map { status(it) }.toSet())
    }

    private companion object {
        const val RACES = 20
    }
}
