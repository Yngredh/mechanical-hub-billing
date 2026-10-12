package com.fiap.mechanicalhub.billing.infrastructure.messaging

import com.fiap.mechanicalhub.billing.application.command.PermanentCommandException
import io.awspring.cloud.sqs.annotation.SqsListener
import io.awspring.cloud.sqs.listener.SqsHeaders
import io.awspring.cloud.sqs.listener.Visibility
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.messaging.Message
import org.springframework.stereotype.Component
import kotlin.math.min

/**
 * Consome `mechanical-hub-billing-commands.fifo` com retry bloqueante: a mensagem só é apagada quando o
 * método retorna (depois do COMMIT do caso de uso). Erro transitório relança e ajusta o visibility timeout
 * pela `ApproximateReceiveCount`; erro permanente vai direto à DLQ e a original é apagada.
 */
@Component
@ConditionalOnProperty("billing.consumer.enabled", havingValue = "true", matchIfMissing = true)
class SqsCommandListener(
    private val processor: CommandProcessor,
    private val sqsTemplate: SqsTemplate,
    @Value("\${billing.queues.commands-dlq}") private val deadLetterQueue: String,
) {
    @SqsListener(
        value = ["\${billing.queues.commands}"],
        pollTimeoutSeconds = "20",
        messageVisibilitySeconds = "30",
    )
    @Suppress("TooGenericExceptionCaught")
    fun onMessage(message: Message<String>, visibility: Visibility) {
        try {
            processor.process(message.payload)
        } catch (e: PermanentCommandException) {
            log.error("Comando com erro permanente, enviando para a DLQ: {}", e.message)
            sendToDeadLetterQueue(message)
        } catch (e: Exception) {
            val receiveCount = message.headers[SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT]
            val delay = backoffSeconds(receiveCount?.toString()?.toIntOrNull() ?: 1)
            log.warn("Erro transitório, nova tentativa em {}s: {}", delay, e.message)
            visibility.changeTo(delay)
            throw e
        }
    }

    private fun sendToDeadLetterQueue(message: Message<String>) {
        val group = message.headers[SqsHeaders.MessageSystemAttributes.SQS_MESSAGE_GROUP_ID_HEADER]?.toString()
        val dedup =
            message.headers[SqsHeaders.MessageSystemAttributes.SQS_MESSAGE_DEDUPLICATION_ID_HEADER]?.toString()
                ?: message.headers[SqsHeaders.MessageSystemAttributes.MESSAGE_ID]?.toString()
        sqsTemplate.send { to ->
            to.queue(deadLetterQueue).payload(message.payload)
            group?.let { to.messageGroupId(it) }
            dedup?.let { to.messageDeduplicationId(it) }
        }
    }

    companion object {
        private const val MAX_BACKOFF_SECONDS = 8
        private const val MAX_BACKOFF_EXPONENT = 4
        private val log = LoggerFactory.getLogger(SqsCommandListener::class.java)

        /** 1 s, 2 s, 4 s e 8 s a partir da quarta tentativa. */
        fun backoffSeconds(receiveCount: Int): Int {
            val exponent = (receiveCount - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
            return min(1 shl exponent, MAX_BACKOFF_SECONDS)
        }
    }
}
