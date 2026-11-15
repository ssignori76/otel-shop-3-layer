package com.commerce.synthetic.checkout;

import com.commerce.synthetic.messaging.RabbitTopologyConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class CheckoutConfig {

    @Bean
    public TopicExchange ordersExchange() {
        return new TopicExchange(RabbitTopologyConstants.ORDERS_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange ordersDlx() {
        return new TopicExchange(RabbitTopologyConstants.ORDERS_DLX, true, false);
    }

    @Bean
    public Queue fulfillmentQueue() {
        return QueueBuilder.durable(RabbitTopologyConstants.FULFILLMENT_QUEUE)
                .withArgument("x-dead-letter-exchange", RabbitTopologyConstants.ORDERS_DLX)
                .withArgument("x-dead-letter-routing-key", RabbitTopologyConstants.ORDER_CREATED_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue fulfillmentDlq() {
        return QueueBuilder.durable(RabbitTopologyConstants.FULFILLMENT_DLQ).build();
    }

    @Bean
    public Binding fulfillmentBinding(Queue fulfillmentQueue, TopicExchange ordersExchange) {
        return BindingBuilder.bind(fulfillmentQueue)
                .to(ordersExchange)
                .with(RabbitTopologyConstants.ORDER_CREATED_ROUTING_KEY);
    }

    @Bean
    public Binding fulfillmentDlqBinding(Queue fulfillmentDlq, TopicExchange ordersDlx) {
        return BindingBuilder.bind(fulfillmentDlq)
                .to(ordersDlx)
                .with(RabbitTopologyConstants.ORDER_CREATED_ROUTING_KEY);
    }

    @Bean
    public RestClient merchantRestClient(
            @Value("${commerce.merchant.base-url:http://merchant-service:8083}") String merchantBaseUrl,
            @Value("${commerce.scenario.merchant-timeout-ms:1500}") int merchantTimeoutMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(merchantTimeoutMs);
        factory.setReadTimeout(merchantTimeoutMs);
        return RestClient.builder()
                .baseUrl(merchantBaseUrl)
                .requestFactory(factory)
                .build();
    }
}
