package at.aau.se2.skyjo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SkyjoApplication

fun main(args: Array<String>) {
    runApplication<SkyjoApplication>(*args)
}
