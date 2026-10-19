package com.fiap.mechanicalhub.billing.domain.payment

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PaymentTest {
    private val now = Instant.parse("2026-10-12T10:00:00Z")
    private val pending = Payment(PaymentStatusEnum.PENDING, "created")

    @Test
    fun `given pendente when aprovar then muda para APPROVED com paidAt`() {
        val result = pending.approve("processed", now)

        val applied = assertIs<PaymentTransition.Applied>(result)
        assertEquals(PaymentStatusEnum.APPROVED, applied.payment.status)
        assertEquals(now, applied.payment.paidAt)
    }

    @Test
    fun `given pendente when cancelar then muda para CANCELED com canceledAt`() {
        val result = pending.cancel("canceled", now)

        val applied = assertIs<PaymentTransition.Applied>(result)
        assertEquals(PaymentStatusEnum.CANCELED, applied.payment.status)
        assertEquals(now, applied.payment.canceledAt)
    }

    @Test
    fun `given cancelado when aprovar then ignora`() {
        val result = pending.copy(status = PaymentStatusEnum.CANCELED).approve("processed", now)

        assertEquals(PaymentTransition.Skipped, result)
    }

    @Test
    fun `given aprovado when cancelar then ignora`() {
        val result = pending.copy(status = PaymentStatusEnum.APPROVED).cancel("canceled", now)

        assertEquals(PaymentTransition.Skipped, result)
    }

    @Test
    fun `given criacao when calcular prazos then due 24h e expires 24h05`() {
        val deadlines = PaymentDeadlines.from(now)

        assertEquals(now + Duration.ofHours(24), deadlines.dueAt)
        assertEquals(now + Duration.ofHours(24).plusMinutes(5), deadlines.expiresAt)
    }
}
