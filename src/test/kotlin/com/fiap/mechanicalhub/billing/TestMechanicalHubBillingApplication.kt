package com.fiap.mechanicalhub.billing

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<MechanicalHubBillingApplication>().with(TestcontainersConfiguration::class).run(*args)
}
