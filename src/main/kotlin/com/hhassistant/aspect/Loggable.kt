package com.hhassistant.aspect

/**
 * Маркерная аннотация для методов, которые должны логироваться через AOP
 * Применяется к методам для автоматического логирования входа, выхода и времени выполнения
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Loggable
