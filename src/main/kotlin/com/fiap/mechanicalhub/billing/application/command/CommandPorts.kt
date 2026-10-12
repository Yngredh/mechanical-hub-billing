package com.fiap.mechanicalhub.billing.application.command

import com.fiap.mechanicalhub.billing.application.payment.OutboxMessage
import com.fiap.mechanicalhub.billing.domain.budget.Budget
import com.fiap.mechanicalhub.billing.domain.budget.MaterialRequest
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Comando `generateBudget`: reparos do diagnóstico e materiais reservados. */
data class GenerateBudgetCommand(
    val transactionId: UUID,
    val orderId: UUID,
    val idempotencyKey: String,
    val repairIds: List<UUID>,
    val materials: List<MaterialRequest>,
)

/** Comando `generatePaymentLink`: orçamento aprovado a cobrar. */
data class GeneratePaymentLinkCommand(
    val transactionId: UUID,
    val orderId: UUID,
    val idempotencyKey: String,
    val budgetId: UUID,
    val orderCost: BigDecimal,
)

/** Erro que não adianta repetir: a mensagem vai direto à DLQ. */
class PermanentCommandException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Entrada da inbox; [response] é a resposta já gravada no outbox pela primeira execução. */
data class InboxEntry(val response: OutboxMessage?)

interface InboxStore {
    /** Limita a espera por locks (lock_timeout de 5 s) na transação corrente. */
    fun limitLockWait()

    /** Registra a chave; devolve `false` se já existia (duplicata). */
    fun claim(key: String): Boolean

    fun find(key: String): InboxEntry?

    fun saveResponse(key: String, response: OutboxMessage)

    /** Apaga entradas processadas antes de [before]; devolve quantas. */
    fun deleteProcessedBefore(before: Instant): Int
}

interface PriceCatalog {
    fun laborPrices(repairIds: Collection<UUID>): Map<UUID, BigDecimal>

    fun materialPrices(materialIds: Collection<UUID>): Map<UUID, BigDecimal>
}

interface BudgetStore {
    fun save(orderId: UUID, transactionId: UUID, budget: Budget): UUID

    fun exists(budgetId: UUID): Boolean
}

/** Pagamento já criado no Mercado Pago, necessário para responder `paymentLinkGenerated`. */
data class PaymentLink(val mpOrderId: String, val checkoutUrl: String, val dueAt: Instant)

data class NewPayment(
    val paymentId: UUID,
    val orderId: UUID,
    val transactionId: UUID,
    val budgetId: UUID,
    val mpOrderId: String,
    val checkoutUrl: String,
    val amount: BigDecimal,
    val dueAt: Instant,
    val expiresAt: Instant,
)

interface PaymentLinkStore {
    fun findPendingByTransaction(transactionId: UUID): PaymentLink?

    fun insert(payment: NewPayment)
}
