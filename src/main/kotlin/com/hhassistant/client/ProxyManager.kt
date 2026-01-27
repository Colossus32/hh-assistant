package com.hhassistant.client

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import reactor.netty.http.client.HttpClient
import java.net.InetSocketAddress
import java.time.Duration

@Component
class ProxyManager(
    @Value("\${hh.proxy.enabled:false}") private val proxyEnabled: Boolean,
    @Value("\${hh.proxy.host:}") private val proxyHost: String,
    @Value("\${hh.proxy.port:8080}") private val proxyPort: Int,
    @Value("\${hh.proxy.type:HTTP}") private val proxyType: String,
    @Value("\${hh.proxy.timeout-seconds}") private val timeoutSeconds: Long
) {
    
    fun getConnector(): ReactorClientHttpConnector {
        val httpClient = if (proxyEnabled && proxyHost.isNotBlank()) {
            when (proxyType.uppercase()) {
                "HTTP", "HTTPS" -> {
                    HttpClient.create()
                        .proxy { proxySpec ->
                            proxySpec.type(
                                reactor.netty.transport.ProxyProvider.Proxy.HTTP
                            ).address(InetSocketAddress(proxyHost, proxyPort))
                        }
                        .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                }
                "SOCKS4", "SOCKS5" -> {
                    HttpClient.create()
                        .proxy { proxySpec ->
                            proxySpec.type(
                                reactor.netty.transport.ProxyProvider.Proxy.SOCKS5
                            ).address(InetSocketAddress(proxyHost, proxyPort))
                        }
                        .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                }
                else -> {
                    HttpClient.create()
                        .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                }
            }
        } else {
            HttpClient.create()
                .responseTimeout(Duration.ofSeconds(30))
        }
        
        return ReactorClientHttpConnector(httpClient)
    }
}

