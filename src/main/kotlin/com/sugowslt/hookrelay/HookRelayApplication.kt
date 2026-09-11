package com.sugowslt.hookrelay

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class HookRelayApplication

fun main(args: Array<String>) {
    runApplication<HookRelayApplication>(*args)
}
