package com.fiap.mechanicalhub.billing.infrastructure.messaging

import com.fiap.mechanicalhub.billing.application.payment.ExpirationSummary
import com.fiap.mechanicalhub.billing.application.payment.ExpirePaymentsUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Connection
import javax.sql.DataSource

/**
 * Roda o vencimento a cada 5 min, só na instância líder. As chamadas ao Mercado Pago não ficam em transação,
 * então a liderança usa lock consultivo de sessão, preso a uma conexão dedicada durante todo o ciclo.
 */
@Suppress("TooGenericExceptionCaught")
@Component
@EnableScheduling
@ConditionalOnProperty("billing.expiration.job.enabled", havingValue = "true", matchIfMissing = true)
class PaymentExpirationJob(
    private val dataSource: DataSource,
    private val useCase: ExpirePaymentsUseCase,
) {
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    fun scheduled() {
        try {
            run()
        } catch (e: Exception) {
            log.error("Falha no ciclo do job de vencimento", e)
        }
    }

    /** Devolve o resumo do ciclo, ou null se esta instância não é a líder. */
    fun run(): ExpirationSummary? =
        dataSource.connection.use { connection ->
            if (!query(connection, "SELECT pg_try_advisory_lock($LOCK_KEY)")) return null
            try {
                useCase.execute().also { log.info("Vencimento: {}", it) }
            } finally {
                query(connection, "SELECT pg_advisory_unlock($LOCK_KEY)")
            }
        }

    private fun query(connection: Connection, sql: String): Boolean =
        connection.createStatement().use { st -> st.executeQuery(sql).use { it.next() && it.getBoolean(1) } }

    private companion object {
        const val LOCK_KEY = 7_000_002L
        val log = LoggerFactory.getLogger(PaymentExpirationJob::class.java)
    }
}
