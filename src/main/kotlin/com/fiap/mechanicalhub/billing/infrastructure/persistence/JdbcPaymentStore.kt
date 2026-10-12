package com.fiap.mechanicalhub.billing.infrastructure.persistence

import com.fiap.mechanicalhub.billing.application.payment.PaymentRecord
import com.fiap.mechanicalhub.billing.application.payment.PaymentStore
import com.fiap.mechanicalhub.billing.domain.payment.Payment
import com.fiap.mechanicalhub.billing.domain.payment.PaymentStatusEnum
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcPaymentStore(private val jdbc: JdbcClient) : PaymentStore {
    override fun findByMpOrderIdForUpdate(mpOrderId: String): PaymentRecord? =
        jdbc
            .sql(
                """
                SELECT payment_id, order_id, transaction_id, mp_order_id, status, mp_status, paid_at, canceled_at
                FROM payments WHERE mp_order_id = :mpOrderId FOR UPDATE
                """.trimIndent(),
            ).param("mpOrderId", mpOrderId)
            .query { rs, _ -> toRecord(rs) }
            .optional()
            .orElse(null)

    override fun updateIfPending(record: PaymentRecord): Boolean =
        jdbc
            .sql(
                """
                UPDATE payments
                SET status = :status, mp_status = :mpStatus, paid_at = :paidAt, canceled_at = :canceledAt
                WHERE payment_id = :paymentId AND status = 'PENDING'
                """.trimIndent(),
            ).param("status", record.payment.status.name)
            .param("mpStatus", record.payment.mpStatus)
            .param("paidAt", toOffset(record.payment.paidAt))
            .param("canceledAt", toOffset(record.payment.canceledAt))
            .param("paymentId", record.paymentId)
            .update() == 1

    private fun toOffset(instant: Instant?): OffsetDateTime? = instant?.atOffset(ZoneOffset.UTC)

    private fun toRecord(rs: ResultSet) =
        PaymentRecord(
            paymentId = rs.getObject("payment_id", UUID::class.java),
            orderId = rs.getObject("order_id", UUID::class.java),
            transactionId = rs.getObject("transaction_id", UUID::class.java),
            mpOrderId = rs.getString("mp_order_id"),
            payment =
                Payment(
                    status = PaymentStatusEnum.valueOf(rs.getString("status")),
                    mpStatus = rs.getString("mp_status"),
                    paidAt = rs.getObject("paid_at", OffsetDateTime::class.java)?.toInstant(),
                    canceledAt = rs.getObject("canceled_at", OffsetDateTime::class.java)?.toInstant(),
                ),
        )
}
