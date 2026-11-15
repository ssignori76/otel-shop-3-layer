package com.commerce.synthetic.gateway;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class GatewayClientConfig {

    @Bean
    public WebClient cartWebClient(
            WebClient.Builder builder,
            @Value("${commerce.gateway.cart-service-url:http://cart-service:8081}") String cartServiceUrl,
            @Value("${commerce.gateway.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${commerce.gateway.response-timeout-ms:15000}") long responseTimeoutMs
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs));
        return builder.clone()
                .baseUrl(cartServiceUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    public WebClient checkoutWebClient(
            WebClient.Builder builder,
            @Value("${commerce.gateway.checkout-service-url:http://checkout-service:8082}") String checkoutServiceUrl,
            @Value("${commerce.gateway.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${commerce.gateway.response-timeout-ms:15000}") long responseTimeoutMs
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs));
        return builder.clone()
                .baseUrl(checkoutServiceUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
