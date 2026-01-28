package com.hhassistant

import io.github.cdimascio.dotenv.Dotenv
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class Application

fun main(args: Array<String>) {
    // Load .env file if it exists
    try {
        val dotenv = Dotenv.configure()
            .directory(".")
            .ignoreIfMissing()
            .load()

        // Set environment variables from .env file
        dotenv.entries().forEach { entry ->
            System.setProperty(entry.key, entry.value)
        }
    } catch (e: Exception) {
        // .env file not found or error loading - continue without it
        // Variables can be set via system environment or application.yml defaults
    }

    runApplication<Application>(*args)
}
