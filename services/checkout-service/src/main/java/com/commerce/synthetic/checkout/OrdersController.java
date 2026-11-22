package com.commerce.synthetic.checkout;

import com.commerce.synthetic.api.OrderSummaryResponse;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/orders")
public class OrdersController {

    private final OrderRepository orderRepository;

    public OrdersController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @GetMapping
    public ResponseEntity<List<OrderSummaryResponse>> listUserOrders(
            @RequestHeader(value = CorrelationHeaders.USER_ID, required = false) String userId
    ) {
        return ResponseEntity.ok(orderRepository.findOrdersByUserId(userId));
    }
}
