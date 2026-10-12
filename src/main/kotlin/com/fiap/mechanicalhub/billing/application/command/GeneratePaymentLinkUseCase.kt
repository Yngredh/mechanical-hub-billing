package com.fiap.mechanicalhub.billing.application.command

import com.fiap.mechanicalhub.billing.application.payment.CreateOrderCommand
import com.fiap.mechanicalhub.billing.application.payment.OutboxMessage
import com.fiap.mechanicalhub.billing.application.payment.PaymentGateway
import com.fiap.mechanicalhub.billing.application.payment.TransactionRunner
import com.fiap.mechanicalhub.billing.domain.BusinessRuleException
import com.fiap.mechanicalhub.billing.domain.payment.PaymentDeadlines
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * TD_finishedOS 14. A chamada ao Mercado Pago fica fora de qualquer transação: antes dela uma
 * transação curta trata duplicata e reaproveita o pagamento PENDING; depois dela, outra grava tudo.
 */
@Service
class GeneratePaymentLinkUseCase(
    private val gateway: PaymentGateway,
    private val links: PaymentLinkStore,
    private val budgets: BudgetStore,
    private val responder: InboxResponder,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    fun execute(command: GeneratePaymentLinkCommand) {
        val handled = transactions.run { reuseExisting(command) }
        if (handled) return

        val deadlines = PaymentDeadlines.from(clock.instant())
        val created =
            gateway.createOrder(
                CreateOrderCommand(
                    orderId = command.orderId.toString(),
                    amount = command.orderCost,
                    expiresAt = deadlines.expiresAt,
                    idempotencyKey = command.idempotencyKey,
                ),
            )
        transactions.run {
            responder.limitLockWait()
            if (!responder.claimOrReplay(command.idempotencyKey)) return@run
            links.insert(
                NewPayment(
                    paymentId = UUID.randomUUID(),
                    orderId = command.orderId,
                    transactionId = command.transactionId,
                    budgetId = command.budgetId,
                    mpOrderId = created.mpOrderId,
                    checkoutUrl = created.checkoutUrl,
                    amount = command.orderCost,
                    dueAt = deadlines.dueAt,
                    expiresAt = deadlines.expiresAt,
                ),
            )
            respond(command, created.checkoutUrl, deadlines.dueAt.toString())
        }
    }

    /** Devolve `true` quando a mensagem já foi resolvida sem chamar o Mercado Pago. */
    private fun reuseExisting(command: GeneratePaymentLinkCommand): Boolean {
        responder.limitLockWait()
        if (responder.replay(command.idempotencyKey)) return true
        if (!budgets.exists(command.budgetId)) {
            throw BusinessRuleException("Orçamento não encontrado: ${command.budgetId}")
        }
        val pending = links.findPendingByTransaction(command.transactionId) ?: return false
        if (responder.claimOrReplay(command.idempotencyKey)) {
            respond(command, pending.checkoutUrl, pending.dueAt.toString())
        }
        return true
    }

    private fun respond(command: GeneratePaymentLinkCommand, paymentLink: String, expiresAt: String) =
        responder.respond(
            command.idempotencyKey,
            OutboxMessage(
                type = "paymentLinkGenerated",
                orderId = command.orderId,
                transactionId = command.transactionId,
                payload =
                    mapOf(
                        "transactionId" to command.transactionId.toString(),
                        "orderId" to command.orderId.toString(),
                        "paymentLink" to paymentLink,
                        "expiresAt" to expiresAt,
                    ),
            ),
        )
}
