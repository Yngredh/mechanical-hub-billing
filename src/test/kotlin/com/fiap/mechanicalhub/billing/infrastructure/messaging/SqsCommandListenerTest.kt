package com.fiap.mechanicalhub.billing.infrastructure.messaging

import com.fiap.mechanicalhub.billing.application.command.GenerateBudgetCommand
import com.fiap.mechanicalhub.billing.application.command.GenerateBudgetUseCase
import com.fiap.mechanicalhub.billing.application.command.GeneratePaymentLinkCommand
import com.fiap.mechanicalhub.billing.application.command.GeneratePaymentLinkUseCase
import com.fiap.mechanicalhub.billing.application.command.PermanentCommandException
import com.fiap.mechanicalhub.billing.application.payment.PermanentGatewayException
import com.fiap.mechanicalhub.billing.application.payment.TransientGatewayException
import com.fiap.mechanicalhub.billing.domain.BusinessRuleException
import io.awspring.cloud.sqs.listener.SqsHeaders
import io.awspring.cloud.sqs.listener.Visibility
import io.awspring.cloud.sqs.operations.SqsTemplate
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqsCommandListenerTest {
    private val budgetUseCase = mockk<GenerateBudgetUseCase>(relaxed = true)
    private val linkUseCase = mockk<GeneratePaymentLinkUseCase>(relaxed = true)
    private val sqs = mockk<SqsTemplate>(relaxed = true)
    private val visibility = mockk<Visibility>(relaxed = true)
    private val listener = SqsCommandListener(CommandProcessor(budgetUseCase, linkUseCase), sqs, "dlq.fifo")

    private val transactionId = UUID.randomUUID()
    private val orderId = UUID.randomUUID()
    private val repairId = UUID.randomUUID()
    private val materialId = UUID.randomUUID()
    private val budgetId = UUID.randomUUID()

    private fun message(body: String, receiveCount: Int = 1): Message<String> =
        MessageBuilder
            .withPayload(body)
            .setHeader(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT, receiveCount.toString())
            .setHeader(SqsHeaders.MessageSystemAttributes.SQS_MESSAGE_GROUP_ID_HEADER, orderId.toString())
            .setHeader(SqsHeaders.MessageSystemAttributes.SQS_MESSAGE_DEDUPLICATION_ID_HEADER, "evt-1")
            .build()

    private val budgetBody =
        """
        {"eventId":"e1","transactionId":"$transactionId","orderId":"$orderId","type":"generateBudget","version":1,
         "idempotencyKey":"$transactionId:GENERATE_BUDGET",
         "repairs":[{"repairId":"$repairId"}],"reservedMaterials":[{"materialId":"$materialId","quantity":3}]}
        """.trimIndent()

    private val linkBody =
        """
        {"transactionId":"$transactionId","orderId":"$orderId","type":"generatePaymentLink",
         "budgetId":"$budgetId","orderCost":150.50}
        """.trimIndent()

    @Test
    fun `given generateBudget when consuming then parses command and calls use case`() {
        val command = slot<GenerateBudgetCommand>()
        every { budgetUseCase.execute(capture(command)) } returns Unit

        listener.onMessage(message(budgetBody), visibility)

        assertEquals(listOf(repairId), command.captured.repairIds)
        assertEquals(
            materialId,
            command.captured.materials
                .single()
                .materialId,
        )
        assertEquals("$transactionId:GENERATE_BUDGET", command.captured.idempotencyKey)
    }

    @Test
    fun `given generatePaymentLink without key when consuming then derives idempotency key`() {
        val command = slot<GeneratePaymentLinkCommand>()
        every { linkUseCase.execute(capture(command)) } returns Unit

        listener.onMessage(message(linkBody), visibility)

        assertEquals("$transactionId:GENERATE_PAYMENT_LINK", command.captured.idempotencyKey)
        assertEquals(budgetId, command.captured.budgetId)
        assertEquals(0, command.captured.orderCost.compareTo("150.50".toBigDecimal()))
    }

    @Test
    fun `given transient failure when consuming then adjusts visibility and rethrows`() {
        every { linkUseCase.execute(any()) } throws TransientGatewayException("timeout")

        assertFailsWith<TransientGatewayException> { listener.onMessage(message(linkBody, receiveCount = 3), visibility) }

        verify { visibility.changeTo(4) }
        verify(exactly = 0) { sqs.send<String>(any()) }
    }

    @Test
    fun `given receive counts when computing backoff then goes from 1s up to 8s`() {
        assertEquals(listOf(1, 2, 4, 8, 8, 8, 8), (1..7).map { SqsCommandListener.backoffSeconds(it) })
    }

    @Test
    fun `given business rule failure when consuming then sends to dlq and does not throw`() {
        every { budgetUseCase.execute(any()) } throws BusinessRuleException("sem preço")

        listener.onMessage(message(budgetBody), visibility)

        verify(exactly = 1) { sqs.send<String>(any()) }
        verify(exactly = 0) { visibility.changeTo(any()) }
    }

    @Test
    fun `given permanent gateway failure when consuming then sends to dlq`() {
        every { linkUseCase.execute(any()) } throws PermanentGatewayException("401")

        listener.onMessage(message(linkBody), visibility)

        verify(exactly = 1) { sqs.send<String>(any()) }
    }

    @Test
    fun `given malformed or unknown command when consuming then sends to dlq`() {
        listener.onMessage(message("not json"), visibility)
        listener.onMessage(message("""{"type":"other"}"""), visibility)
        listener.onMessage(message("""{"type":"generateBudget","orderId":"x"}"""), visibility)

        verify(exactly = 3) { sqs.send<String>(any()) }
    }

    @Test
    fun `given permanent exception type when processing then keeps it`() {
        val processor = CommandProcessor(budgetUseCase, linkUseCase)
        every { budgetUseCase.execute(any()) } throws PermanentCommandException("x")

        assertFailsWith<PermanentCommandException> { processor.process(budgetBody) }
    }
}
