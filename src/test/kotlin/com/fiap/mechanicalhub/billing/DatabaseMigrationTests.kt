package com.fiap.mechanicalhub.billing

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class DatabaseMigrationTests {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `given the migrations when applied then the catalog is seeded`() {
        assertEquals(4, count("service_prices"))
        assertEquals(10, count("material_prices"))
    }

    @Test
    fun `given the seeds when summing the oil change then the budget matches the catalog`() {
        val labor = price("SELECT labor_price FROM service_prices WHERE service_id = '03d94934-6201-449b-b8db-490546c35d34'")
        val oil = price("SELECT unit_price FROM material_prices WHERE material_id = '789492e9-c859-4ad7-8a9a-39d3ffb12519'")
        val filter = price("SELECT unit_price FROM material_prices WHERE material_id = 'eb694988-23ef-413b-b68a-c510f6346534'")

        assertEquals(BigDecimal("308.10"), labor + oil.multiply(BigDecimal(4)) + filter)
    }

    @Test
    fun `given the migrations when applied then control and domain tables exist`() {
        listOf("budgets", "budget_items", "payments", "outbox_events", "inbox_events").forEach {
            assertEquals(0, count(it), it)
        }
    }

    private fun count(table: String): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun price(sql: String): BigDecimal = jdbcTemplate.queryForObject(sql, BigDecimal::class.java)!!
}
