package com.fiap.mechanicalhub.billing.infrastructure.persistence

import com.fiap.mechanicalhub.billing.application.command.BudgetStore
import com.fiap.mechanicalhub.billing.application.command.InboxEntry
import com.fiap.mechanicalhub.billing.application.command.InboxStore
import com.fiap.mechanicalhub.billing.application.command.NewPayment
import com.fiap.mechanicalhub.billing.application.command.PaymentLink
import com.fiap.mechanicalhub.billing.application.command.PaymentLinkStore
import com.fiap.mechanicalhub.billing.application.command.PriceCatalog
import com.fiap.mechanicalhub.billing.application.payment.OutboxMessage
import com.fiap.mechanicalhub.billing.domain.budget.Budget
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcInboxStore(private val jdbc: JdbcClient) : InboxStore {
    private val mapper = JsonMapper.builder().build()

    override fun limitLockWait() {
        jdbc.sql("SET LOCAL lock_timeout = '5s'").update()
    }

    override fun claim(key: String): Boolean =
        jdbc
            .sql("INSERT INTO inbox_events (message_key) VALUES (:key) ON CONFLICT (message_key) DO NOTHING")
            .param("key", key)
            .update() == 1

    override fun find(key: String): InboxEntry? =
        jdbc
            .sql("SELECT response_payload::text AS response FROM inbox_events WHERE message_key = :key")
            .param("key", key)
            .query { rs, _ -> InboxEntry(rs.getString("response")?.let(::fromJson)) }
            .optional()
            .orElse(null)

    override fun saveResponse(key: String, response: OutboxMessage) {
        jdbc
            .sql("UPDATE inbox_events SET response_payload = CAST(:payload AS jsonb) WHERE message_key = :key")
            .param("payload", toJson(response))
            .param("key", key)
            .update()
    }

    override fun deleteProcessedBefore(before: Instant): Int =
        jdbc
            .sql("DELETE FROM inbox_events WHERE processed_at < :before")
            .param("before", before.atOffset(ZoneOffset.UTC))
            .update()

    private fun toJson(message: OutboxMessage): String =
        mapper.writeValueAsString(
            mapOf(
                "type" to message.type,
                "orderId" to message.orderId.toString(),
                "transactionId" to message.transactionId.toString(),
                "payload" to message.payload,
            ),
        )

    @Suppress("UNCHECKED_CAST")
    private fun fromJson(json: String): OutboxMessage {
        val node = mapper.readValue(json, Map::class.java) as Map<String, Any>
        return OutboxMessage(
            type = node.getValue("type") as String,
            orderId = UUID.fromString(node.getValue("orderId") as String),
            transactionId = UUID.fromString(node.getValue("transactionId") as String),
            payload = node.getValue("payload") as Map<String, Any>,
        )
    }
}

@Repository
class JdbcPriceCatalog(private val jdbc: JdbcClient) : PriceCatalog {
    override fun laborPrices(repairIds: Collection<UUID>): Map<UUID, BigDecimal> =
        prices(
            "SELECT service_id AS id, labor_price AS price FROM service_prices WHERE service_id IN (:ids)",
            repairIds,
        )

    override fun materialPrices(materialIds: Collection<UUID>): Map<UUID, BigDecimal> =
        prices(
            "SELECT material_id AS id, unit_price AS price FROM material_prices WHERE material_id IN (:ids)",
            materialIds,
        )

    private fun prices(sql: String, ids: Collection<UUID>): Map<UUID, BigDecimal> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            jdbc
                .sql(sql)
                .param("ids", ids.distinct())
                .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getBigDecimal("price") }
                .list()
                .toMap()
        }
}

@Repository
class JdbcBudgetStore(private val jdbc: JdbcClient) : BudgetStore {
    override fun save(orderId: UUID, transactionId: UUID, budget: Budget): UUID {
        val budgetId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO budgets (budget_id, order_id, transaction_id, labor_total, material_total, total)
                VALUES (:budgetId, :orderId, :transactionId, :laborTotal, :materialTotal, :total)
                """.trimIndent(),
            ).param("budgetId", budgetId)
            .param("orderId", orderId)
            .param("transactionId", transactionId)
            .param("laborTotal", budget.laborTotal)
            .param("materialTotal", budget.materialTotal)
            .param("total", budget.orderCost)
            .update()
        budget.items.forEach { item ->
            jdbc
                .sql(
                    """
                    INSERT INTO budget_items (id, budget_id, item_type, item_ref, quantity, unit_price, total_price)
                    VALUES (:id, :budgetId, :type, :itemRef, :quantity, :unitPrice, :totalPrice)
                    """.trimIndent(),
                ).param("id", UUID.randomUUID())
                .param("budgetId", budgetId)
                .param("type", item.type.name)
                .param("itemRef", item.itemRef)
                .param("quantity", item.quantity.intValueExact())
                .param("unitPrice", item.unitPrice)
                .param("totalPrice", item.totalPrice)
                .update()
        }
        return budgetId
    }

    override fun exists(budgetId: UUID): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM budgets WHERE budget_id = :budgetId)")
            .param("budgetId", budgetId)
            .query(Boolean::class.java)
            .single()
}

@Repository
class JdbcPaymentLinkStore(private val jdbc: JdbcClient) : PaymentLinkStore {
    override fun findPendingByTransaction(transactionId: UUID): PaymentLink? =
        jdbc
            .sql(
                """
                SELECT mp_order_id, checkout_url, due_at FROM payments
                WHERE transaction_id = :transactionId AND status = 'PENDING'
                ORDER BY created_at LIMIT 1
                """.trimIndent(),
            ).param("transactionId", transactionId)
            .query { rs, _ ->
                PaymentLink(
                    mpOrderId = rs.getString("mp_order_id"),
                    checkoutUrl = rs.getString("checkout_url"),
                    dueAt = rs.getObject("due_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)

    override fun insert(payment: NewPayment) {
        jdbc
            .sql(
                """
                INSERT INTO payments (payment_id, order_id, transaction_id, budget_id, mp_order_id, checkout_url,
                                      amount, status, due_at, expires_at)
                VALUES (:paymentId, :orderId, :transactionId, :budgetId, :mpOrderId, :checkoutUrl,
                        :amount, 'PENDING', :dueAt, :expiresAt)
                """.trimIndent(),
            ).param("paymentId", payment.paymentId)
            .param("orderId", payment.orderId)
            .param("transactionId", payment.transactionId)
            .param("budgetId", payment.budgetId)
            .param("mpOrderId", payment.mpOrderId)
            .param("checkoutUrl", payment.checkoutUrl)
            .param("amount", payment.amount)
            .param("dueAt", payment.dueAt.atOffset(ZoneOffset.UTC))
            .param("expiresAt", payment.expiresAt.atOffset(ZoneOffset.UTC))
            .update()
    }
}
