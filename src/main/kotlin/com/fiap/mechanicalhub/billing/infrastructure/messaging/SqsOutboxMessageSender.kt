package com.fiap.mechanicalhub.billing.infrastructure.messaging

import com.fiap.mechanicalhub.billing.application.payment.OutboxMessageSender
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SqsOutboxMessageSender(
    private val sqsTemplate: SqsTemplate,
    @Value("\${billing.queues.events}") private val queue: String,
) : OutboxMessageSender {
    override fun send(groupId: UUID, deduplicationId: UUID, body: String) {
        sqsTemplate.send { to ->
            to
                .queue(queue)
                .payload(body)
                .messageGroupId(groupId.toString())
                .messageDeduplicationId(deduplicationId.toString())
        }
    }
}
