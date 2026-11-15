package com.commerce.synthetic.generator;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/generator")
public class GeneratorController {

    private final JourneyGeneratorService generatorService;
    private final GeneratorProperties properties;

    public GeneratorController(JourneyGeneratorService generatorService, GeneratorProperties properties) {
        this.generatorService = generatorService;
        this.properties = properties;
    }

    public record TriggerRequest(
            @JsonProperty("run_id") String runId,
            @JsonProperty("journey_id") String journeyId,
            @JsonProperty("scenario_name") String scenarioName,
            @JsonProperty("scenario_enabled") Boolean scenarioEnabled
    ) {
    }

    @PostMapping("/trigger")
    public ResponseEntity<JourneyExecutionRecord> triggerJourney(@RequestBody(required = false) TriggerRequest request) {
        String runId = request != null ? request.runId() : null;
        String journeyId = request != null ? request.journeyId() : null;
        String scenario = request != null ? request.scenarioName() : null;
        Boolean enabled = request != null ? request.scenarioEnabled() : null;

        JourneyExecutionRecord record = generatorService.executeSingleJourney(runId, journeyId, scenario, enabled);
        return ResponseEntity.ok(record);
    }

    @GetMapping("/history")
    public ResponseEntity<List<JourneyExecutionRecord>> getHistory() {
        return ResponseEntity.ok(generatorService.getHistory());
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "enabled", properties.isEnabled(),
                "interval_seconds", properties.getIntervalSeconds(),
                "initial_delay_seconds", properties.getInitialDelaySeconds(),
                "max_in_flight", properties.getMaxInFlight(),
                "in_flight", generatorService.isInFlight(),
                "executed_count", generatorService.getHistory().size(),
                "configured_scenario", properties.getScenarioName(),
                "configured_scenario_enabled", properties.isScenarioEnabled()
        ));
    }
}
