package com.IDDagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import io.netty.resolver.DefaultAddressResolverGroup;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        // 使用 JDK 系统解析器（getaddrinfo）而非 Netty 自带 UDP 53 直连解析：
        // 部分网络环境（如公司网/防火墙）拦截 UDP 53 导致 DnsNameResolver 超时，
        // JDK resolver 走 Windows DNS 客户端（缓存 + TCP 回退），兼容性更好
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .responseTimeout(Duration.ofSeconds(120));//将默认的超时时间从几秒钟延长到了 120 秒。

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
