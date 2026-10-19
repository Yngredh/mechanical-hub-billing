package com.fiap.mechanicalhub.billing.infrastructure.messaging

import com.fiap.mechanicalhub.billing.TestcontainersConfiguration
import com.fiap.mechanicalhub.billing.application.payment.ExpirationSummary
import com.fiap.mechanicalhub.billing.application.payment.ExpirePaymentsUseCase
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class PaymentExpirationJobIntegrationTest {
    @Autowired private lateinit var dataSource: DataSource

    private val useCase = mockk<ExpirePaymentsUseCase>()

    @Test
    fun `given another instance holding leadership when running then skips the cycle`() {
        every { useCase.execute() } returns ExpirationSummary(0, 0, 0, 0)
        val job = PaymentExpirationJob(dataSource, useCase)

        dataSource.connection.use { other ->
            other.createStatement().use { it.execute("SELECT pg_advisory_lock(7000002)") }
            assertNull(job.run())
            other.createStatement().use { it.execute("SELECT pg_advisory_unlock(7000002)") }
        }

        assertNotNull(job.run())
        assertEquals(ExpirationSummary(0, 0, 0, 0), job.run())
    }
}
