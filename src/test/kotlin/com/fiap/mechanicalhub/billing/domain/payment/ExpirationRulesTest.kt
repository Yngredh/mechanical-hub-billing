package com.fiap.mechanicalhub.billing.domain.payment

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ExpirationRulesTest {
    private fun conflict(status: String, detail: String? = null) =
        ExpirationRules.decide(CancelOutcome.Conflict, OrderLookup.Found(status, detail))

    @Test
    fun `given cancelamento aceito when decidir then falha EXPIRED (A)`() {
        val decision = ExpirationRules.decide(CancelOutcome.Canceled)

        assertEquals(ExpirationDecision.Fail(PaymentFailureReason.EXPIRED, "canceled"), decision)
    }

    @Test
    fun `given conflito e processed accredited when decidir then confirma (B1)`() {
        assertEquals(ExpirationDecision.Confirm("processed"), conflict("processed", "accredited"))
    }

    @Test
    fun `given conflito e canceled when decidir then falha EXPIRED (B2)`() {
        assertEquals(ExpirationDecision.Fail(PaymentFailureReason.EXPIRED, "canceled"), conflict("canceled"))
    }

    @Test
    fun `given conflito e failed when decidir then falha FAILED (B3)`() {
        assertEquals(ExpirationDecision.Fail(PaymentFailureReason.FAILED, "failed"), conflict("failed"))
    }

    @Test
    fun `given conflito e processing when decidir then aguarda (B4)`() {
        assertEquals(ExpirationDecision.Wait, conflict("processing"))
    }

    @Test
    fun `given conflito e created when decidir then aguarda`() {
        assertEquals(ExpirationDecision.Wait, conflict("created"))
    }

    @Test
    fun `given conflito e consulta transitoria when decidir then aguarda`() {
        assertEquals(ExpirationDecision.Wait, ExpirationRules.decide(CancelOutcome.Conflict, OrderLookup.Transient))
    }

    @Test
    fun `given cancelamento transitorio when decidir then aguarda`() {
        assertEquals(ExpirationDecision.Wait, ExpirationRules.decide(CancelOutcome.Transient))
    }

    @Test
    fun `given cancelamento permanente when decidir then alerta`() {
        assertEquals(ExpirationDecision.Alert, ExpirationRules.decide(CancelOutcome.Permanent))
    }
}
