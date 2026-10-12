package com.fiap.mechanicalhub.billing.infrastructure.messaging

import com.fiap.mechanicalhub.billing.application.command.GenerateBudgetCommand
import com.fiap.mechanicalhub.billing.application.command.GenerateBudgetUseCase
import com.fiap.mechanicalhub.billing.application.command.GeneratePaymentLinkCommand
import com.fiap.mechanicalhub.billing.application.command.GeneratePaymentLinkUseCase
import com.fiap.mechanicalhub.billing.application.command.PermanentCommandException
import com.fiap.mechanicalhub.billing.application.payment.PermanentGatewayException
import com.fiap.mechanicalhub.billing.domain.BusinessRuleException
import com.fiap.mechanicalhub.billing.domain.budget.MaterialRequest
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Interpreta o envelope JSON do comando e chama o caso de uso. Erros que não adiantam repetir
 * viram [PermanentCommandException]; qualquer outra exceção é tratada como transitória.
 */
@Component
class CommandProcessor(
    private val generateBudget: GenerateBudgetUseCase,
    private val generatePaymentLink: GeneratePaymentLinkUseCase,
) {
    private val mapper = JsonMapper.builder().build()

    @Suppress("TooGenericExceptionCaught")
    fun process(body: String) {
        try {
            dispatch(body)
        } catch (e: Exception) {
            throw translate(e)
        }
    }

    private fun dispatch(body: String) {
        val root = mapper.readTree(body)
        when (val type = text(root, "type")) {
            "generateBudget" -> generateBudget.execute(budgetCommand(root))
            "generatePaymentLink" -> generatePaymentLink.execute(paymentLinkCommand(root))
            else -> throw PermanentCommandException("Comando desconhecido: $type")
        }
    }

    private fun translate(e: Exception): Exception =
        if (isPermanent(e) && e !is PermanentCommandException) {
            PermanentCommandException(e.message ?: e.javaClass.name, e)
        } else {
            e
        }

    private fun isPermanent(e: Exception) =
        e is PermanentCommandException ||
            e is BusinessRuleException ||
            e is PermanentGatewayException ||
            e is IllegalArgumentException ||
            e is ArithmeticException ||
            e is JacksonException

    private fun budgetCommand(root: JsonNode): GenerateBudgetCommand {
        val transactionId = uuid(root, "transactionId")
        return GenerateBudgetCommand(
            transactionId = transactionId,
            orderId = uuid(root, "orderId"),
            idempotencyKey = idempotencyKey(root, transactionId, "GENERATE_BUDGET"),
            repairIds = root.path("repairs").values().map { repairId(it) },
            materials =
                root.path("reservedMaterials").values().map {
                    MaterialRequest(UUID.fromString(text(it, "materialId")), it.path("quantity").decimalValue())
                },
        )
    }

    private fun paymentLinkCommand(root: JsonNode): GeneratePaymentLinkCommand {
        val transactionId = uuid(root, "transactionId")
        return GeneratePaymentLinkCommand(
            transactionId = transactionId,
            orderId = uuid(root, "orderId"),
            idempotencyKey = idempotencyKey(root, transactionId, "GENERATE_PAYMENT_LINK"),
            budgetId = uuid(root, "budgetId"),
            orderCost = root.path("orderCost").decimalValue(),
        )
    }

    /** O reparo chega como UUID puro ou como objeto `{repairId}`. */
    private fun repairId(node: JsonNode): UUID =
        UUID.fromString(if (node.isObject) text(node, "repairId") else node.asString())

    private fun idempotencyKey(root: JsonNode, transactionId: UUID, step: String): String =
        root.path("idempotencyKey").asString("").ifBlank { "$transactionId:$step" }

    private fun uuid(node: JsonNode, field: String): UUID = UUID.fromString(text(node, field))

    private fun text(node: JsonNode, field: String): String =
        node.path(field).asString("").also { require(it.isNotBlank()) { "Campo obrigatório ausente: $field" } }
}
