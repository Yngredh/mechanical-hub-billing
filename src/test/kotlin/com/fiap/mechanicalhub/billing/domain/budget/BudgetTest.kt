package com.fiap.mechanicalhub.billing.domain.budget

import com.fiap.mechanicalhub.billing.domain.BusinessRuleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals

class BudgetTest {
    private val repairId = UUID.randomUUID()
    private val materialId = UUID.randomUUID()

    @Test
    fun `given precos when calcular then soma mao de obra e materiais`() {
        val laborPrices = mapOf(repairId to BigDecimal("150.00"))
        val materialPrices = mapOf(materialId to BigDecimal("12.50"))

        val budget =
            Budget.calculate(
                listOf(repairId),
                listOf(MaterialRequest(materialId, BigDecimal("3"))),
                laborPrices,
                materialPrices,
            )

        assertEquals(BigDecimal("150.00"), budget.laborTotal)
        assertEquals(BigDecimal("37.50"), budget.materialTotal)
        assertEquals(BigDecimal("187.50"), budget.orderCost)
    }

    @Test
    fun `given precos when calcular then itens congelam preco unitario`() {
        val budget =
            Budget.calculate(
                listOf(repairId),
                listOf(MaterialRequest(materialId, BigDecimal("2"))),
                mapOf(repairId to BigDecimal("100")),
                mapOf(materialId to BigDecimal("5.5")),
            )

        val material = budget.items.single { it.type == BudgetItemType.MATERIAL }
        assertEquals(BigDecimal("5.50"), material.unitPrice)
        assertEquals(BigDecimal("11.00"), material.totalPrice)
        assertEquals(2, budget.items.size)
    }

    @Test
    fun `given reparo sem preco when calcular then BusinessRuleException`() {
        assertThrows<BusinessRuleException> {
            Budget.calculate(listOf(repairId), emptyList(), emptyMap(), emptyMap())
        }
    }

    @Test
    fun `given material sem preco when calcular then BusinessRuleException`() {
        assertThrows<BusinessRuleException> {
            Budget.calculate(
                emptyList(),
                listOf(MaterialRequest(materialId, BigDecimal.ONE)),
                emptyMap(),
                emptyMap(),
            )
        }
    }
}
