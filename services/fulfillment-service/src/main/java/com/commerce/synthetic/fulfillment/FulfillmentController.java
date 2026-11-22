package com.commerce.synthetic.fulfillment;

import com.commerce.synthetic.api.ApiErrorResponse;
import com.commerce.synthetic.api.FulfillmentStatusResponse;
import com.commerce.synthetic.scenario.TelemetryHelper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/internal/fulfillments")
public class FulfillmentController {

    private final FulfillmentRepository fulfillmentRepository;

    public FulfillmentController(FulfillmentRepository fulfillmentRepository) {
        this.fulfillmentRepository = fulfillmentRepository;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<?> getByOrderId(@PathVariable("orderId") String orderId) {
        Optional<FulfillmentStatusResponse> found = fulfillmentRepository.findByOrderId(orderId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiErrorResponse.of(
                            HttpStatus.NOT_FOUND.value(),
                            "FULFILLMENT_PENDING",
                            "Fulfillment not yet completed for order " + orderId,
                            "",
                            "",
                            TelemetryHelper.currentTraceId()
                    )
            );
        }
        return ResponseEntity.ok(found.get());
    }
}
