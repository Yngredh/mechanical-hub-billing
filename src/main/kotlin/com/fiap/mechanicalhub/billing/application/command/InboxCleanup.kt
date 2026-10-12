package com.fiap.mechanicalhub.billing.application.command

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/** Job diário que apaga `inbox_events` com mais de 30 dias. */
@Component
@EnableScheduling
class InboxCleanup(
    private val inbox: InboxStore,
    private val clock: Clock,
) {
    @Scheduled(cron = "0 0 3 * * *")
    fun scheduled() {
        log.info("Inbox: {} entradas antigas removidas", cleanup())
    }

    fun cleanup(): Int = inbox.deleteProcessedBefore(clock.instant().minus(RETENTION))

    private companion object {
        val RETENTION: Duration = Duration.ofDays(30)
        val log = LoggerFactory.getLogger(InboxCleanup::class.java)
    }
}
