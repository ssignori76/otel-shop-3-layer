package com.commerce.synthetic.messaging;

/**
 * Canonical RabbitMQ topology constants per Section 13.1 of the specification.
 */
public final class RabbitTopologyConstants {

    public static final String ORDERS_EXCHANGE = "commerce.orders";
    public static final String ORDER_CREATED_ROUTING_KEY = "order.created";
    public static final String FULFILLMENT_QUEUE = "commerce.fulfillment";
    public static final String ORDERS_DLX = "commerce.orders.dlx";
    public static final String FULFILLMENT_DLQ = "commerce.fulfillment.dlq";
    public static final String SCHEMA_VERSION_V1 = "1.0";

    private RabbitTopologyConstants() {
    }
}
