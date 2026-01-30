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
 * AOP аспект для централизованного логирования методов сервисов
 */
@Aspect
@Component
class LoggingAspect {
    private val log = KotlinLogging.logger {}

    /**
     * Точка среза для всех публичных методов в сервисах
     */
    @Pointcut("execution(public * com.hhassistant.service..*(..))")
    fun serviceMethods() {}

    /**
     * Точка среза для всех публичных методов в клиентах
     */
    @Pointcut("execution(public * com.hhassistant.client..*(..))")
    fun clientMethods() {}

    /**
     * Логирование выполнения методов с измерением времени
     */
    @Around("serviceMethods() || clientMethods()")
    fun logMethodExecution(joinPoint: ProceedingJoinPoint): Any? {
        val signature = joinPoint.signature as MethodSignature
        val className = signature.declaringType.simpleName
        val methodName = signature.name
        val args = joinPoint.args

        // Логируем вход в метод
        log.debug { "▶️ [$className] Entering method: $methodName(${formatArguments(args)})" }

        return try {
            val result: Any?
            val duration = measureTimeMillis {
                result = joinPoint.proceed()
            }

            // Логируем успешное выполнение
            log.debug { "✅ [$className] Method $methodName completed in ${duration}ms" }
            result
        } catch (e: Exception) {
            // Логируем ошибку
            log.error(e) { "❌ [$className] Method $methodName failed: ${e.message}" }
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
                else -> arg.toString().take(AppConstants.TextLimits.LOG_ARGUMENT_PREVIEW_LENGTH)
            }
        }
    }
}

