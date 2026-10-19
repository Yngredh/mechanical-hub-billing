package com.fiap.mechanicalhub.billing.application.payment

import com.fiap.mechanicalhub.billing.domain.payment.CancelOutcome
import com.fiap.mechanicalhub.billing.domain.payment.OrderLookup
import com.fiap.mechanicalhub.billing.domain.payment.Payment
import com.fiap.mechanicalhub.billing.domain.payment.PaymentStatusEnum
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class ExpirePaymentsUseCaseTest {
    private val now = Instant.parse("2026-01-02T10:00:00Z")
    private val gateway = mockk<PaymentGateway>()
    private val store = mockk<PaymentStore>(relaxed = true)
    private val outbox = mockk<EventOutbox>(relaxed = true)
    private val transactions =
        object : TransactionRunner {
            override fun <T> run(block: () -> T): T = block()
        }
    private val useCase = ExpirePaymentsUseCase(gateway, store, outbox, transactions, Clock.fixed(now, ZoneOffset.UTC))
    private val record =
        PaymentRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ORD1", Payment(PaymentStatusEnum.PENDING, null))
    private val key = "${record.transactionId}:CANCEL_MP_ORDER"

    private fun givenDue() {
        every { store.findDue(now, 100) } returns listOf(record)
        every { store.findByMpOrderIdForUpdate("ORD1") } returns record
        every { store.updateIfPending(any()) } returns true
    }

    @Test
    fun `given canceled order when executing then cancels payment and publishes paymentFailed EXPIRED`() {
        givenDue()
        every { gateway.cancelOrder("ORD1", key) } returns CancelOutcome.Canceled
        val message = slot<OutboxMessage>()

        val summary = useCase.execute()

        assertEquals(1, summary.failed)
        verify { outbox.publish(capture(message)) }
        assertEquals("paymentFailed", message.captured.type)
        assertEquals("EXPIRED", message.captured.payload["reason"])
        verify { store.updateIfPending(match { it.payment.status == PaymentStatusEnum.CANCELED }) }
    }

    @Test
    fun `given conflict and paid order when executing then approves and publishes paymentConfirmed`() {
        givenDue()
        every { gateway.cancelOrder("ORD1", key) } returns CancelOutcome.Conflict
        every { gateway.getOrder("ORD1") } returns OrderLookup.Found("processed", "accredited")
        val message = slot<OutboxMessage>()

        val summary = useCase.execute()

        assertEquals(1, summary.confirmed)
        verify { outbox.publish(capture(message)) }
        assertEquals("paymentConfirmed", message.captured.type)
        assertEquals(record.paymentId.toString(), message.captured.payload["paymentId"])
    }

    @Test
    fun `given conflict and failed order when executing then publishes paymentFailed FAILED`() {
        givenDue()
        every { gateway.cancelOrder("ORD1", key) } returns CancelOutcome.Conflict
        every { gateway.getOrder("ORD1") } returns OrderLookup.Found("failed", null)
        val message = slot<OutboxMessage>()

        useCase.execute()

        verify { outbox.publish(capture(message)) }
        assertEquals("FAILED", message.captured.payload["reason"])
    }

    @Test
    fun `given transient error when executing then waits without writing`() {
        givenDue()
        every { gateway.cancelOrder("ORD1", key) } returns CancelOutcome.Transient

        val summary = useCase.execute()

        assertEquals(1, summary.waiting)
        verify(exactly = 0) { store.updateIfPending(any()) }
        verify(exactly = 0) { outbox.publish(any()) }
    }

    @Test
    fun `given permanent error when executing then alerts without writing`() {
        givenDue()
        every { gateway.cancelOrder("ORD1", key) } returns CancelOutcome.Permanent

        val summary = useCase.execute()

        assertEquals(1, summary.alerts)
        verify(exactly = 0) { outbox.publish(any()) }
    }

    @Test
    fun `given payment no longer pending when finalizing then skips outbox`() {
        givenDue()
        every { store.findByMpOrderIdForUpdate("ORD1") } returns record.copy(payment = Payment(PaymentStatusEnum.APPROVED, "processed"))
        every { gateway.cancelOrder("ORD1", key) } returns CancelOutcome.Canceled

        val summary = useCase.execute()

        assertEquals(0, summary.failed)
        verify(exactly = 0) { outbox.publish(any()) }
    }
}
