package com.fiap.mechanicalhub.billing.infrastructure.messaging

import com.fiap.mechanicalhub.billing.TestcontainersConfiguration
import com.fiap.mechanicalhub.billing.application.payment.EventOutbox
import com.fiap.mechanicalhub.billing.application.payment.OutboxMessage
import com.fiap.mechanicalhub.billing.application.payment.OutboxMessageSender
import com.fiap.mechanicalhub.billing.application.payment.TransactionRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingSender : OutboxMessageSender {
    val sent = mutableListOf<Triple<UUID, UUID, String>>()

    @Volatile
    var failOn: Int? = null

    override fun send(groupId: UUID, deduplicationId: UUID, body: String) {
        if (failOn == sent.size + 1) error("sqs indisponivel")
        sent += Triple(groupId, deduplicationId, body)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class RecordingSenderConfig {
    @Bean
    @Primary
    fun recordingSender() = RecordingSender()
}

@Import(TestcontainersConfiguration::class, RecordingSenderConfig::class)
@SpringBootTest
class OutboxRelayIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var jdbcClient: JdbcClient

    @Autowired private lateinit var outbox: EventOutbox

    @Autowired private lateinit var sender: RecordingSender

    @Autowired private lateinit var transactions: TransactionRunner

    private lateinit var relay: OutboxRelay

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM outbox_events")
        sender.sent.clear()
        sender.failOn = null
        relay = OutboxRelay(jdbcClient, transactions, sender)
    }

    private fun publish(count: Int, orderId: UUID = UUID.randomUUID()) =
        repeat(count) {
            transactions.run {
                outbox.publish(OutboxMessage("paymentConfirmed", orderId, UUID.randomUUID(), mapOf("n" to it)))
            }
        }

    private fun count(where: String) = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE $where", Int::class.java)

    @Test
    fun `given pending events when relaying then sends in order and marks sent`() {
        val order = UUID.randomUUID()
        publish(3, order)

        assertEquals(3, relay.relay())

        assertEquals(3, sender.sent.size)
        assertTrue(sender.sent.all { it.first == order })
        assertEquals(0, count("status = 'PENDING'"))
        assertEquals(0, relay.relay())
    }

    @Test
    fun `given more than a batch when relaying then sends at most 100`() {
        publish(105)

        assertEquals(100, relay.relay())
        assertEquals(5, relay.relay())
    }

    @Test
    fun `given send failure when relaying then stops batch and increments attempts`() {
        publish(3)
        sender.failOn = 2

        assertEquals(1, relay.relay())

        assertEquals(1, count("status = 'SENT'"))
        assertEquals(1, count("attempts = 1 AND last_error IS NOT NULL"))
        assertEquals(2, count("status = 'PENDING'"))
    }
}
