package com.hhassistant.client

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.net.InetSocketAddress
import java.time.Duration

@Component
class ProxyManager(
    @Value("\${hh.proxy.enabled:false}") private val proxyEnabled: Boolean,
    @Value("\${hh.proxy.host:}") private val proxyHost: String,
    @Value("\${hh.proxy.port:8080}") private val proxyPort: Int,
    @Value("\${hh.proxy.type:HTTP}") private val proxyType: String,
    @Value("\${hh.proxy.timeout-seconds}") private val timeoutSeconds: Long,
) {
    // Connection pool для переиспользования соединений
    // Настройки оптимизированы для частых HTTP запросов к HH.ru API
    private val connectionProvider: ConnectionProvider = ConnectionProvider.builder("hh-api-pool")
        .maxConnections(200) // Максимум одновременных соединений
        .maxIdleTime(Duration.ofSeconds(20)) // Время простоя перед закрытием
        .maxLifeTime(Duration.ofMinutes(30)) // Максимальное время жизни соединения
        .pendingAcquireTimeout(Duration.ofSeconds(45)) // Таймаут ожидания свободного соединения
        .evictInBackground(Duration.ofSeconds(120)) // Периодическая очистка неактивных соединений
        .build()

    fun getConnector(): ReactorClientHttpConnector {
        val baseHttpClient = HttpClient.create(connectionProvider)
            .responseTimeout(Duration.ofSeconds(timeoutSeconds))
            .followRedirect(true) // Следовать редиректам

        val httpClient = if (proxyEnabled && proxyHost.isNotBlank()) {
            when (proxyType.uppercase()) {
                "HTTP", "HTTPS" -> {
                    baseHttpClient.proxy { proxySpec ->
                        proxySpec.type(
                            reactor.netty.transport.ProxyProvider.Proxy.HTTP,
                        ).address(InetSocketAddress(proxyHost, proxyPort))
                    }
                }
                "SOCKS4", "SOCKS5" -> {
                    baseHttpClient.proxy { proxySpec ->
                        proxySpec.type(
                            reactor.netty.transport.ProxyProvider.Proxy.SOCKS5,
                        ).address(InetSocketAddress(proxyHost, proxyPort))
                    }
                }
                else -> {
                    baseHttpClient
                }
            }
        } else {
            baseHttpClient
        }

        return ReactorClientHttpConnector(httpClient)
    }
}
