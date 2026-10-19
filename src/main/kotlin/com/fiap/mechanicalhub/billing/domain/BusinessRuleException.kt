package com.fiap.mechanicalhub.billing.domain

/** Violação permanente de regra de negócio: a mensagem não deve ser reprocessada. */
class BusinessRuleException(message: String) : RuntimeException(message)
