package com.hhassistant.aspect

import com.hhassistant.config.AppConstants
import mu.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.stereotype.Component
import kotlin.system.measureTimeMillis

/**
 * AOP aspect for centralized logging of methods annotated with @Loggable
 * Provides performance measurement and error logging
 */
@Aspect
@Component
class LoggingAspect {
    private val log = KotlinLogging.logger {}

    /**
     * Threshold for logging slow methods at INFO level (in milliseconds)
     */
    private val slowMethodThreshold = 100L

    /**
     * Logs method execution with timing measurement for methods annotated with @Loggable
     * - TRACE: method entry with arguments
     * - DEBUG: method completion (fast methods)
     * - INFO: method completion (slow methods, > threshold)
     * - ERROR: exceptions
     */
    @Around("@annotation(com.hhassistant.aspect.Loggable)")
    fun logMethodExecution(joinPoint: ProceedingJoinPoint): Any? {
        val signature = joinPoint.signature as MethodSignature
        val className = signature.declaringType.simpleName
        val methodName = signature.name

        val args = joinPoint.args

        // Log method entry only at trace level to reduce noise
        log.trace { "[$className] Entering method: $methodName(${formatArguments(args)})" }

        return try {
            val result: Any?
            val duration = measureTimeMillis {
                result = joinPoint.proceed()
            }

            // Log slow methods at INFO level, others at DEBUG
            if (duration >= slowMethodThreshold) {
                log.info { "[$className] Method $methodName completed in ${duration}ms (slow)" }
            } else {
                log.debug { "[$className] Method $methodName completed in ${duration}ms" }
            }
            result
        } catch (e: Exception) {
            // Always log errors
            log.error(e) { "[$className] Method $methodName failed: ${e.message}" }
            throw e
        }
    }

    /**
     * Форматирует аргументы метода для логирования
     */
    private fun formatArguments(args: Array<Any?>): String {
        if (args.isEmpty()) return ""

        return args.joinToString(", ") { arg ->
            when (arg) {
                null -> "null"
                is String -> if (arg.length > AppConstants.TextLimits.LOG_ARGUMENT_PREVIEW_LENGTH) "\"${arg.take(AppConstants.TextLimits.LOG_ARGUMENT_PREVIEW_LENGTH)}...\"" else "\"$arg\""
                is Collection<*> -> "${arg.javaClass.simpleName}(size=${arg.size})"
                is Array<*> -> "${arg.javaClass.simpleName}(size=${arg.size})"
                // Для data classes и других объектов показываем только имя класса, чтобы избежать JSON в логах
                else -> "${arg.javaClass.simpleName}@${System.identityHashCode(arg).toString(16)}"
            }
        }
    }
}