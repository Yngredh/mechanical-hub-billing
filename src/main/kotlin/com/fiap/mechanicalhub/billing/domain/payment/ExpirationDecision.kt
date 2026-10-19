package com.fiap.mechanicalhub.billing.domain.payment

enum class PaymentFailureReason { EXPIRED, FAILED }

/** Resposta do Mercado Pago ao cancelamento da order (F2). */
sealed interface CancelOutcome {
    data object Canceled : CancelOutcome

    data object Conflict : CancelOutcome

    data object Transient : CancelOutcome

    data object Permanent : CancelOutcome
}

/** Resposta da consulta da order (caso B). [Transient] cobre timeout, 5xx e 429. */
sealed interface OrderLookup {
    data class Found(val status: String, val statusDetail: String?) : OrderLookup

    data object Transient : OrderLookup

    data object Permanent : OrderLookup
}

sealed interface ExpirationDecision {
    /** Finaliza como falha (F3). */
    data class Fail(val reason: PaymentFailureReason, val mpStatus: String) : ExpirationDecision

    /** Finaliza como sucesso (F3'). */
    data class Confirm(val mpStatus: String) : ExpirationDecision

    /** Nada a fazer; próximo ciclo. */
    data object Wait : ExpirationDecision

    /** Erro permanente: log de erro e alerta. */
    data object Alert : ExpirationDecision
}

object ExpirationRules {
    private const val CANCELED = "canceled"
    private const val PROCESSED = "processed"
    private const val FAILED = "failed"
    private const val ACCREDITED = "accredited"

    /** Decisão do job após o cancelamento; [lookup] só é usado no caso B (conflito). */
    fun decide(cancel: CancelOutcome, lookup: OrderLookup? = null): ExpirationDecision =
        when (cancel) {
            CancelOutcome.Canceled -> ExpirationDecision.Fail(PaymentFailureReason.EXPIRED, CANCELED)
            CancelOutcome.Transient -> ExpirationDecision.Wait
            CancelOutcome.Permanent -> ExpirationDecision.Alert
            CancelOutcome.Conflict -> lookup?.let(::decideAfterConflict) ?: ExpirationDecision.Wait
        }

    private fun decideAfterConflict(lookup: OrderLookup): ExpirationDecision =
        when (lookup) {
            OrderLookup.Transient -> ExpirationDecision.Wait
            OrderLookup.Permanent -> ExpirationDecision.Alert
            is OrderLookup.Found -> decideByStatus(lookup)
        }

    private fun decideByStatus(order: OrderLookup.Found): ExpirationDecision =
        when {
            order.status == PROCESSED && order.statusDetail == ACCREDITED -> ExpirationDecision.Confirm(PROCESSED)
            order.status == CANCELED -> ExpirationDecision.Fail(PaymentFailureReason.EXPIRED, CANCELED)
            order.status == FAILED -> ExpirationDecision.Fail(PaymentFailureReason.FAILED, FAILED)
            else -> ExpirationDecision.Wait
        }
}
