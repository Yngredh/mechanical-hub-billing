package com.fiap.mechanicalhub.billing.application.command

import com.fiap.mechanicalhub.billing.TestcontainersConfiguration
import com.fiap.mechanicalhub.billing.application.payment.CreateOrderCommand
import com.fiap.mechanicalhub.billing.application.payment.CreatedOrder
import com.fiap.mechanicalhub.billing.application.payment.PaymentGateway
import com.fiap.mechanicalhub.billing.application.payment.TransientGatewayException
import com.fiap.mechanicalhub.billing.domain.BusinessRuleException
import com.fiap.mechanicalhub.billing.domain.budget.MaterialRequest
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
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class RecordingGateway : PaymentGateway {
    val created = mutableListOf<CreateOrderCommand>()

    @Volatile
    var failure: RuntimeException? = null

    override fun createOrder(command: CreateOrderCommand): CreatedOrder {
        failure?.let { throw it }
        created += command
        return CreatedOrder("ORD-${command.idempotencyKey}", "https://checkout/${command.orderId}")
    }

    override fun getOrder(mpOrderId: String): OrderLookup = error("not used")

    override fun cancelOrder(mpOrderId: String, idempotencyKey: String): CancelOutcome = error("not used")
}

@TestConfiguration(proxyBeanMethods = false)
class RecordingGatewayConfig {
    @Bean
    @Primary
    fun recordingGateway() = RecordingGateway()
}

@Import(TestcontainersConfiguration::class, RecordingGatewayConfig::class)
@SpringBootTest
class CommandUseCasesIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var budgetUseCase: GenerateBudgetUseCase

    @Autowired private lateinit var linkUseCase: GeneratePaymentLinkUseCase

    @Autowired private lateinit var gateway: RecordingGateway

    @Autowired private lateinit var cleanup: InboxCleanup

    private val transactionId = UUID.randomUUID()
    private val orderId = UUID.randomUUID()
    private val repairId = UUID.randomUUID()
    private val materialId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM outbox_events")
        jdbc.update("DELETE FROM inbox_events")
        jdbc.update("DELETE FROM payments")
        jdbc.update("DELETE FROM budget_items")
        jdbc.update("DELETE FROM budgets")
        gateway.created.clear()
        gateway.failure = null
        jdbc.update("INSERT INTO service_prices (service_id, labor_price) VALUES (?, 100.00)", repairId)
        jdbc.update("INSERT INTO material_prices (material_id, unit_price) VALUES (?, 12.50)", materialId)
    }

    private fun budgetCommand(key: String = "$transactionId:GENERATE_BUDGET", repair: UUID = repairId) =
        GenerateBudgetCommand(
            transactionId,
            orderId,
            key,
            listOf(repair),
            listOf(MaterialRequest(materialId, BigDecimal(3))),
        )

    private fun outboxTypes() = jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY sequence", String::class.java)

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)

    private fun generateBudget(): UUID {
        budgetUseCase.execute(budgetCommand())
        return jdbc.queryForObject("SELECT budget_id FROM budgets WHERE transaction_id = ?", UUID::class.java, transactionId)!!
    }

    @Test
    fun `given prices when generating budget then persists frozen budget and responds`() {
        generateBudget()

        assertEquals(
            BigDecimal("137.50"),
            jdbc.queryForObject("SELECT total FROM budgets WHERE order_id = ?", BigDecimal::class.java, orderId),
        )
        assertEquals(2, count("budget_items"))
        assertEquals(listOf("budgetGenerated"), outboxTypes())
        val payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events", String::class.java)!!
        assertEquals(true, payload.contains("\"orderCost\": 137.50") || payload.contains("\"orderCost\":137.50"))
        assertNotNull(jdbc.queryForObject("SELECT response_payload::text FROM inbox_events", String::class.java))
    }

    @Test
    fun `given duplicate generateBudget when consuming then replays response without new budget`() {
        generateBudget()
        budgetUseCase.execute(budgetCommand())

        assertEquals(1, count("budgets"))
        assertEquals(listOf("budgetGenerated", "budgetGenerated"), outboxTypes())
        assertEquals(1, count("inbox_events"))
    }

    @Test
    fun `given repair without price when generating budget then fails and leaves no trace`() {
        assertFailsWith<BusinessRuleException> { budgetUseCase.execute(budgetCommand(repair = UUID.randomUUID())) }

        assertEquals(0, count("budgets"))
        assertEquals(0, count("inbox_events"))
        assertEquals(0, count("outbox_events"))
    }

    private fun linkCommand(budgetId: UUID) =
        GeneratePaymentLinkCommand(
            transactionId,
            orderId,
            "$transactionId:GENERATE_PAYMENT_LINK",
            budgetId,
            BigDecimal("137.50"),
        )

    @Test
    fun `given budget when generating payment link then calls MP once and stores pending payment`() {
        val budgetId = generateBudget()

        linkUseCase.execute(linkCommand(budgetId))

        assertEquals(1, gateway.created.size)
        assertEquals("$transactionId:GENERATE_PAYMENT_LINK", gateway.created.single().idempotencyKey)
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payments", String::class.java))
        assertEquals(listOf("budgetGenerated", "paymentLinkGenerated"), outboxTypes())
    }

    @Test
    fun `given duplicate generatePaymentLink when consuming then replays without calling MP`() {
        val budgetId = generateBudget()
        linkUseCase.execute(linkCommand(budgetId))
        linkUseCase.execute(linkCommand(budgetId))

        assertEquals(1, gateway.created.size)
        assertEquals(1, count("payments"))
        assertEquals(listOf("budgetGenerated", "paymentLinkGenerated", "paymentLinkGenerated"), outboxTypes())
    }

    @Test
    fun `given pending payment of the transaction when generating link with new key then reuses it`() {
        val budgetId = generateBudget()
        linkUseCase.execute(linkCommand(budgetId))

        linkUseCase.execute(linkCommand(budgetId).copy(idempotencyKey = "other-key"))

        assertEquals(1, gateway.created.size)
        assertEquals(1, count("payments"))
        assertEquals(3, outboxTypes().size)
    }

    @Test
    fun `given MP transient failure when generating link then nothing is recorded`() {
        val budgetId = generateBudget()
        gateway.failure = TransientGatewayException("timeout")

        assertFailsWith<TransientGatewayException> { linkUseCase.execute(linkCommand(budgetId)) }

        assertEquals(0, count("payments"))
        assertEquals(1, count("inbox_events"))
        assertEquals(listOf("budgetGenerated"), outboxTypes())
    }

    @Test
    fun `given unknown budget when generating link then fails with business rule`() {
        assertFailsWith<BusinessRuleException> { linkUseCase.execute(linkCommand(UUID.randomUUID())) }

        assertEquals(0, gateway.created.size)
    }

    @Test
    fun `given old and recent inbox entries when cleaning up then removes only older than 30 days`() {
        jdbc.update("INSERT INTO inbox_events (message_key, processed_at) VALUES ('old', now() - interval '31 days')")
        jdbc.update("INSERT INTO inbox_events (message_key, processed_at) VALUES ('recent', now() - interval '29 days')")

        assertEquals(1, cleanup.cleanup())

        assertEquals(listOf("recent"), jdbc.queryForList("SELECT message_key FROM inbox_events", String::class.java))
    }
}
