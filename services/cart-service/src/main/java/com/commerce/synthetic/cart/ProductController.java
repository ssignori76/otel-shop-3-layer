package com.commerce.synthetic.cart;

import com.commerce.synthetic.api.ProductResponse;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.commerce.synthetic.scenario.JourneyState;
import com.commerce.synthetic.scenario.ScenarioContext;
import com.commerce.synthetic.scenario.TelemetryHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final CartRepository cartRepository;
    private final String defaultScenario;
    private final boolean defaultScenarioEnabled;

    public ProductController(
            CartRepository cartRepository,
            @Value("${commerce.scenario.name:NORMAL}") String defaultScenario,
            @Value("${commerce.scenario.enabled:false}") boolean defaultScenarioEnabled
    ) {
        this.cartRepository = cartRepository;
        this.defaultScenario = defaultScenario;
        this.defaultScenarioEnabled = defaultScenarioEnabled;
    }

    @GetMapping
    public ResponseEntity<List<ProductResponse>> listProducts(
            @RequestHeader(value = CorrelationHeaders.RUN_ID, required = false) String headerRunId,
            @RequestHeader(value = CorrelationHeaders.JOURNEY_ID, required = false) String headerJourneyId,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_SCENARIO, required = false) String headerScenario,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_ENABLED, required = false) String headerEnabled
    ) {
        String runId = (headerRunId != null && !headerRunId.isBlank()) ? headerRunId : UUID.randomUUID().toString();
        String journeyId = (headerJourneyId != null && !headerJourneyId.isBlank()) ? headerJourneyId : UUID.randomUUID().toString();
        ScenarioContext ctx = ScenarioContext.resolve(
                runId, journeyId, headerScenario, headerEnabled, defaultScenario, defaultScenarioEnabled
        );
        try {
            TelemetryHelper.bindContext(ctx, JourneyState.PAGE_LOADED);
            List<ProductResponse> products = cartRepository.listProducts();
            log.info("Catalog products fetched: count={}", products.size());
            return ResponseEntity.ok(products);
        } finally {
            TelemetryHelper.clearMdc();
        }
    }
}
