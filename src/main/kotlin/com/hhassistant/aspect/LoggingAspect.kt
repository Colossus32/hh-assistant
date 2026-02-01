package com.hhassistant.aspect

import com.hhassistant.config.AppConstants
import mu.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.stereotype.Component
import kotlin.system.measureTimeMillis

/**
 * AOP aspect for centralized logging of service and client methods with performance measurement
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
     * Pointcut for all public methods in services
     */
    @Pointcut("execution(public * com.hhassistant.service..*(..))")
    fun serviceMethods() {}

    /**
     * Pointcut for all public methods in clients
     */
    @Pointcut("execution(public * com.hhassistant.client..*(..))")
    fun clientMethods() {}

    /**
     * Logs method execution with timing measurement
     * - DEBUG: all method calls
     * - INFO: methods taking longer than threshold
     * - ERROR: exceptions
     */
    @Around("serviceMethods() || clientMethods()")
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
