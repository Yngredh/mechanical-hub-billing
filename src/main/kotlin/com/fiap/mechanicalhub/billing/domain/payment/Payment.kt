package com.fiap.mechanicalhub.billing.domain.payment

import java.time.Duration
import java.time.Instant

enum class PaymentStatusEnum { PENDING, APPROVED, CANCELED }

/** Prazos do pagamento: negócio (24h) e expiração da order no Mercado Pago (24h05). */
data class PaymentDeadlines(val dueAt: Instant, val expiresAt: Instant, val expiresIn: Duration) {
    companion object {
        val DUE_DELAY: Duration = Duration.ofHours(24)
        val EXPIRATION_DELAY: Duration = Duration.ofHours(24).plusMinutes(5)

        val EXPIRATION_GRACE: Duration = Duration.ofMinutes(5)

        fun from(
            createdAt: Instant,
            dueDelay: Duration = DUE_DELAY,
            grace: Duration = EXPIRATION_GRACE,
        ) = PaymentDeadlines(createdAt + dueDelay, createdAt + dueDelay + grace, dueDelay + grace)
    }
}

/** Resultado de uma transição condicional; [Skipped] significa que o pagamento já não estava PENDING. */
sealed interface PaymentTransition {
    data class Applied(val payment: Payment) : PaymentTransition

    data object Skipped : PaymentTransition
}

data class Payment(
    val status: PaymentStatusEnum,
    val mpStatus: String?,
    val paidAt: Instant? = null,
    val canceledAt: Instant? = null,
) {
    fun approve(mpStatus: String, now: Instant): PaymentTransition =
        if (status != PaymentStatusEnum.PENDING) {
            PaymentTransition.Skipped
        } else {
            PaymentTransition.Applied(copy(status = PaymentStatusEnum.APPROVED, mpStatus = mpStatus, paidAt = now))
        }

    fun cancel(mpStatus: String, now: Instant): PaymentTransition =
        if (status != PaymentStatusEnum.PENDING) {
            PaymentTransition.Skipped
        } else {
            PaymentTransition.Applied(copy(status = PaymentStatusEnum.CANCELED, mpStatus = mpStatus, canceledAt = now))
        }
}
