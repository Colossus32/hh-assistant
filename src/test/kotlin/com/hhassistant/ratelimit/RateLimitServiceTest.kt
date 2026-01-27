package com.hhassistant.ratelimit

import com.hhassistant.exception.HHAPIException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RateLimitServiceTest {

    private lateinit var rateLimitService: RateLimitService

    @BeforeEach
    fun setup() {
        // Создаем сервис с низкими лимитами для тестирования
        rateLimitService = RateLimitService(
            requestsPerMinute = 2,
            burstCapacity = 3,
            waitOnLimitSeconds = 1,
        )
    }

    @Test
    fun `should allow requests within limit`() = runBlocking {
        // Первые 3 запроса должны пройти (burst capacity)
        assertThat(rateLimitService.tryConsume()).isTrue()
        assertThat(rateLimitService.tryConsume()).isTrue()
        assertThat(rateLimitService.tryConsume()).isTrue()
    }

    @Test
    fun `should track available tokens`() {
        val initialTokens = rateLimitService.getAvailableTokens()
        assertThat(initialTokens).isGreaterThanOrEqualTo(2L) // At least requests per minute

        runBlocking {
            rateLimitService.tryConsume()
        }

        val remainingTokens = rateLimitService.getAvailableTokens()
        assertThat(remainingTokens).isLessThan(initialTokens)
    }

    @Test
    fun `should track used tokens`() {
        val initialTokens = rateLimitService.getAvailableTokens()
        val initialUsed = rateLimitService.getUsedTokens()
        assertThat(initialUsed).isGreaterThanOrEqualTo(0L)

        runBlocking {
            rateLimitService.tryConsume()
            rateLimitService.tryConsume()
        }

        val usedAfter = rateLimitService.getUsedTokens()
        assertThat(usedAfter).isGreaterThan(initialUsed)
        assertThat(rateLimitService.getAvailableTokens()).isLessThan(initialTokens)
    }

    @Test
    fun `should throw exception when limit exceeded after waiting`() = runBlocking {
        // Исчерпываем все токены
        rateLimitService.tryConsume()
        rateLimitService.tryConsume()
        rateLimitService.tryConsume()

        // Следующий запрос должен выбросить исключение после ожидания
        assertThatThrownBy {
            runBlocking {
                rateLimitService.tryConsume()
            }
        }.isInstanceOf(HHAPIException.RateLimitException::class.java)
    }

    @Test
    fun `should reset bucket`() {
        runBlocking {
            rateLimitService.tryConsume()
            rateLimitService.tryConsume()
        }

        val tokensBeforeReset = rateLimitService.getAvailableTokens()

        rateLimitService.reset()

        val tokensAfterReset = rateLimitService.getAvailableTokens()
        assertThat(tokensAfterReset).isGreaterThan(tokensBeforeReset)
        assertThat(tokensAfterReset).isGreaterThanOrEqualTo(2L) // At least requests per minute restored
    }
}
