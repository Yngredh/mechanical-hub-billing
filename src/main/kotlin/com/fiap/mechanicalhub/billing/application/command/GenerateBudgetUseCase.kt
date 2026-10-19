package com.fiap.mechanicalhub.billing.application.command

import com.fiap.mechanicalhub.billing.application.payment.OutboxMessage
import com.fiap.mechanicalhub.billing.application.payment.TransactionRunner
import com.fiap.mechanicalhub.billing.domain.budget.Budget
import org.springframework.stereotype.Service

/** TD_finishedOS 9: calcula e grava o orçamento com o preço do momento e responde `budgetGenerated`. */
@Service
class GenerateBudgetUseCase(
    private val prices: PriceCatalog,
    private val budgets: BudgetStore,
    private val inbox: InboxStore,
    private val responder: InboxResponder,
    private val transactions: TransactionRunner,
) {
    fun execute(command: GenerateBudgetCommand) {
        transactions.run {
            inbox.limitLockWait()
            if (!responder.claimOrReplay(command.idempotencyKey)) return@run
            val budget =
                Budget.calculate(
                    repairIds = command.repairIds,
                    materials = command.materials,
                    laborPrices = prices.laborPrices(command.repairIds),
                    materialPrices = prices.materialPrices(command.materials.map { it.materialId }),
                )
            val budgetId = budgets.save(command.orderId, command.transactionId, budget)
            responder.respond(
                command.idempotencyKey,
                OutboxMessage(
                    type = "budgetGenerated",
                    orderId = command.orderId,
                    transactionId = command.transactionId,
                    payload =
                        mapOf(
                            "transactionId" to command.transactionId.toString(),
                            "orderId" to command.orderId.toString(),
                            "budgetId" to budgetId.toString(),
                            "orderCost" to budget.orderCost,
                        ),
                ),
            )
        }
    }
}
