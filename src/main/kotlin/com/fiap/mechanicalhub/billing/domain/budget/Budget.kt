package com.fiap.mechanicalhub.billing.domain.budget

import com.fiap.mechanicalhub.billing.domain.BusinessRuleException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

enum class BudgetItemType { REPAIR, MATERIAL }

data class MaterialRequest(val materialId: UUID, val quantity: BigDecimal)

data class BudgetItem(
    val type: BudgetItemType,
    val itemRef: UUID,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val totalPrice: BigDecimal,
)

data class Budget(
    val items: List<BudgetItem>,
    val laborTotal: BigDecimal,
    val materialTotal: BigDecimal,
    val orderCost: BigDecimal,
) {
    companion object {
        private const val MONEY_SCALE = 2

        /**
         * Calcula o orçamento congelando o preço do momento em cada item.
         * Reparo ou material sem preço no catálogo gera [BusinessRuleException].
         */
        fun calculate(
            repairIds: List<UUID>,
            materials: List<MaterialRequest>,
            laborPrices: Map<UUID, BigDecimal>,
            materialPrices: Map<UUID, BigDecimal>,
        ): Budget {
            val repairItems = repairIds.map { repairItem(it, laborPrices) }
            val materialItems = materials.map { materialItem(it, materialPrices) }
            val laborTotal = repairItems.sumOf { it.totalPrice }
            val materialTotal = materialItems.sumOf { it.totalPrice }
            return Budget(repairItems + materialItems, laborTotal, materialTotal, laborTotal + materialTotal)
        }

        private fun repairItem(repairId: UUID, laborPrices: Map<UUID, BigDecimal>): BudgetItem {
            val price =
                laborPrices[repairId]
                    ?: throw BusinessRuleException("Preço de mão de obra não encontrado: reparo $repairId")
            return BudgetItem(BudgetItemType.REPAIR, repairId, BigDecimal.ONE, money(price), money(price))
        }

        private fun materialItem(request: MaterialRequest, materialPrices: Map<UUID, BigDecimal>): BudgetItem {
            val price =
                materialPrices[request.materialId]
                    ?: throw BusinessRuleException("Preço unitário não encontrado: material ${request.materialId}")
            require(request.quantity.signum() > 0) { "Quantidade inválida: material ${request.materialId}" }
            return BudgetItem(
                type = BudgetItemType.MATERIAL,
                itemRef = request.materialId,
                quantity = request.quantity,
                unitPrice = money(price),
                totalPrice = money(price.multiply(request.quantity)),
            )
        }

        private fun money(value: BigDecimal): BigDecimal = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
    }
}
