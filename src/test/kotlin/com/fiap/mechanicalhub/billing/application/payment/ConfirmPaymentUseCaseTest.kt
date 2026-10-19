package com.fiap.mechanicalhub.billing.application.payment

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

class ConfirmPaymentUseCaseTest {
    private val now = Instant.parse("2026-01-01T10:00:00Z")
    private val gateway = mockk<PaymentGateway>()
    private val store = mockk<PaymentStore>(relaxed = true)
    private val outbox = mockk<EventOutbox>(relaxed = true)
    private val transactions =
        object : TransactionRunner {
            override fun <T> run(block: () -> T): T = block()
        }
    private val useCase = ConfirmPaymentUseCase(gateway, store, outbox, transactions, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `given order paid and payment pending when executing then approves and publishes paymentConfirmed`() {
        val record = record(PaymentStatusEnum.PENDING)
        every { gateway.getOrder(MP_ORDER) } returns OrderLookup.Found("processed", "accredited")
        every { store.findByMpOrderIdForUpdate(MP_ORDER) } returns record
        every { store.updateIfPending(any()) } returns true
        val message = slot<OutboxMessage>()

        val result = useCase.execute(MP_ORDER)

        assertEquals(WebhookResult.CONFIRMED, result)
        verify { outbox.publish(capture(message)) }
        assertEquals("paymentConfirmed", message.captured.type)
        assertEquals(record.paymentId.toString(), message.captured.payload["paymentId"])
    }

    @Test
    fun `given payment already approved when executing then ignores without outbox`() {
        every { gateway.getOrder(MP_ORDER) } returns OrderLookup.Found("processed", "accredited")
        every { store.findByMpOrderIdForUpdate(MP_ORDER) } returns record(PaymentStatusEnum.APPROVED)

        val result = useCase.execute(MP_ORDER)

        assertEquals(WebhookResult.IGNORED, result)
        verify(exactly = 0) { outbox.publish(any()) }
    }

    @Test
    fun `given payment canceled and order paid when executing then ignores without outbox`() {
        every { gateway.getOrder(MP_ORDER) } returns OrderLookup.Found("processed", "accredited")
        every { store.findByMpOrderIdForUpdate(MP_ORDER) } returns record(PaymentStatusEnum.CANCELED)

        val result = useCase.execute(MP_ORDER)

        assertEquals(WebhookResult.IGNORED, result)
        verify(exactly = 0) { outbox.publish(any()) }
    }

    @Test
    fun `given row changed concurrently when update is skipped then no outbox`() {
        every { gateway.getOrder(MP_ORDER) } returns OrderLookup.Found("processed", "accredited")
        every { store.findByMpOrderIdForUpdate(MP_ORDER) } returns record(PaymentStatusEnum.PENDING)
        every { store.updateIfPending(any()) } returns false

        val result = useCase.execute(MP_ORDER)

        assertEquals(WebhookResult.IGNORED, result)
        verify(exactly = 0) { outbox.publish(any()) }
    }

    @Test
    fun `given order not paid when executing then ignores without touching the database`() {
        every { gateway.getOrder(MP_ORDER) } returns OrderLookup.Found("action_required", "waiting_payment")

        val result = useCase.execute(MP_ORDER)

        assertEquals(WebhookResult.IGNORED, result)
        verify(exactly = 0) { store.findByMpOrderIdForUpdate(any()) }
    }

    @Test
    fun `given unknown order in database when executing then ignores`() {
        every { gateway.getOrder(MP_ORDER) } returns OrderLookup.Found("processed", "accredited")
        every { store.findByMpOrderIdForUpdate(MP_ORDER) } returns null

        assertEquals(WebhookResult.IGNORED, useCase.execute(MP_ORDER))
    }

    @Test
    fun `given transient lookup failure when executing then asks for retry`() {
        every { gateway.getOrder(MP_ORDER) } returns OrderLookup.Transient

        assertEquals(WebhookResult.RETRY, useCase.execute(MP_ORDER))
    }

    @Test
    fun `given permanent lookup failure when executing then ignores`() {
        every { gateway.getOrder(MP_ORDER) } returns OrderLookup.Permanent

        assertEquals(WebhookResult.IGNORED, useCase.execute(MP_ORDER))
    }

    private fun record(status: PaymentStatusEnum) =
        PaymentRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), MP_ORDER, Payment(status, null))

    private companion object {
        const val MP_ORDER = "ORD01"
    }
}
