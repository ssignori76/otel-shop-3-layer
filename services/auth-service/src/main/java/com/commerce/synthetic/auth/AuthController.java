package com.commerce.synthetic.auth;

import com.commerce.synthetic.api.ApiErrorResponse;
import com.commerce.synthetic.api.AuthResponse;
import com.commerce.synthetic.api.JwtTokenProvider;
import com.commerce.synthetic.api.LoginRequest;
import com.commerce.synthetic.api.RegisterRequest;
import com.commerce.synthetic.api.ResetPasswordRequest;
import com.commerce.synthetic.api.UserProfileResponse;
import com.commerce.synthetic.scenario.CorrelationHeaders;
import com.commerce.synthetic.scenario.JourneyState;
import com.commerce.synthetic.scenario.ScenarioContext;
import com.commerce.synthetic.scenario.TelemetryHelper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/internal/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final String defaultScenario;
    private final boolean defaultScenarioEnabled;

    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            @Value("${commerce.scenario.name:NORMAL}") String defaultScenario,
            @Value("${commerce.scenario.enabled:false}") boolean defaultScenarioEnabled
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.defaultScenario = defaultScenario;
        this.defaultScenarioEnabled = defaultScenarioEnabled;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(
            @Valid @RequestBody RegisterRequest request,
            @RequestHeader(value = CorrelationHeaders.RUN_ID, required = false) String headerRunId,
            @RequestHeader(value = CorrelationHeaders.JOURNEY_ID, required = false) String headerJourneyId,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_SCENARIO, required = false) String headerScenario,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_ENABLED, required = false) String headerEnabled
    ) {
        ScenarioContext ctx = resolveContext(headerRunId, headerJourneyId, headerScenario, headerEnabled);
        try {
            TelemetryHelper.bindContext(ctx, JourneyState.STARTED);
            String normalizedEmail = UserRepository.normalizeEmail(request.email());

            if (userRepository.findByEmail(normalizedEmail).isPresent()) {
                TelemetryHelper.incrementCounter(
                        "commerce.auth.registrations",
                        "{registration}",
                        "Total user registration attempts in auth-service",
                        Map.of("auth.outcome", "conflict")
                );
                log.warn("Registration rejected: email already registered");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                        ApiErrorResponse.of(
                                HttpStatus.CONFLICT.value(),
                                "EMAIL_ALREADY_EXISTS",
                                "An account with this email already exists",
                                ctx.runId(),
                                ctx.journeyId(),
                                TelemetryHelper.currentTraceId()
                        )
                );
            }

            String passwordHash = passwordEncoder.encode(request.password());
            UserRecord created;
            try {
                created = userRepository.createUser(normalizedEmail, request.fullName(), passwordHash);
            } catch (DuplicateKeyException ex) {
                TelemetryHelper.incrementCounter(
                        "commerce.auth.registrations",
                        "{registration}",
                        "Total user registration attempts in auth-service",
                        Map.of("auth.outcome", "conflict")
                );
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                        ApiErrorResponse.of(
                                HttpStatus.CONFLICT.value(),
                                "EMAIL_ALREADY_EXISTS",
                                "An account with this email already exists",
                                ctx.runId(),
                                ctx.journeyId(),
                                TelemetryHelper.currentTraceId()
                        )
                );
            }

            String token = jwtTokenProvider.generateToken(created.userId(), created.email(), created.fullName());
            TelemetryHelper.incrementCounter(
                    "commerce.auth.registrations",
                    "{registration}",
                    "Total user registration attempts in auth-service",
                    Map.of("auth.outcome", "success")
            );
            log.info("User registered successfully: user_id={}", created.userId());
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    new AuthResponse(token, "Bearer", jwtTokenProvider.getExpirationSeconds(), created.toProfile())
            );
        } finally {
            TelemetryHelper.clearMdc();
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(value = CorrelationHeaders.RUN_ID, required = false) String headerRunId,
            @RequestHeader(value = CorrelationHeaders.JOURNEY_ID, required = false) String headerJourneyId,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_SCENARIO, required = false) String headerScenario,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_ENABLED, required = false) String headerEnabled
    ) {
        ScenarioContext ctx = resolveContext(headerRunId, headerJourneyId, headerScenario, headerEnabled);
        try {
            TelemetryHelper.bindContext(ctx, JourneyState.STARTED);
            Optional<UserRecord> found = userRepository.findByEmail(request.email());
            if (found.isEmpty() || !passwordEncoder.matches(request.password(), found.get().passwordHash())) {
                TelemetryHelper.incrementCounter(
                        "commerce.auth.logins",
                        "{login}",
                        "Total user login attempts in auth-service",
                        Map.of("auth.outcome", "failure")
                );
                log.warn("Login failed: invalid credentials");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                        ApiErrorResponse.of(
                                HttpStatus.UNAUTHORIZED.value(),
                                "INVALID_CREDENTIALS",
                                "Invalid email or password",
                                ctx.runId(),
                                ctx.journeyId(),
                                TelemetryHelper.currentTraceId()
                        )
                );
            }

            UserRecord user = found.get();
            String token = jwtTokenProvider.generateToken(user.userId(), user.email(), user.fullName());
            TelemetryHelper.incrementCounter(
                    "commerce.auth.logins",
                    "{login}",
                    "Total user login attempts in auth-service",
                    Map.of("auth.outcome", "success")
            );
            log.info("User logged in successfully: user_id={}", user.userId());
            return ResponseEntity.ok(
                    new AuthResponse(token, "Bearer", jwtTokenProvider.getExpirationSeconds(), user.toProfile())
            );
        } finally {
            TelemetryHelper.clearMdc();
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(
            @RequestHeader(value = CorrelationHeaders.USER_ID, required = false) String userId,
            @RequestHeader(value = CorrelationHeaders.RUN_ID, required = false) String headerRunId,
            @RequestHeader(value = CorrelationHeaders.JOURNEY_ID, required = false) String headerJourneyId,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_SCENARIO, required = false) String headerScenario,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_ENABLED, required = false) String headerEnabled
    ) {
        ScenarioContext ctx = resolveContext(headerRunId, headerJourneyId, headerScenario, headerEnabled);
        try {
            TelemetryHelper.bindContext(ctx, JourneyState.PAGE_LOADED);
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                        ApiErrorResponse.of(
                                HttpStatus.UNAUTHORIZED.value(),
                                "UNAUTHORIZED",
                                "Authentication required",
                                ctx.runId(),
                                ctx.journeyId(),
                                TelemetryHelper.currentTraceId()
                        )
                );
            }
            Optional<UserRecord> found = userRepository.findById(userId);
            if (found.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                        ApiErrorResponse.of(
                                HttpStatus.UNAUTHORIZED.value(),
                                "USER_NOT_FOUND",
                                "Authenticated user no longer exists",
                                ctx.runId(),
                                ctx.journeyId(),
                                TelemetryHelper.currentTraceId()
                        )
                );
            }
            UserProfileResponse profile = found.get().toProfile();
            return ResponseEntity.ok(profile);
        } finally {
            TelemetryHelper.clearMdc();
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            @RequestHeader(value = CorrelationHeaders.RUN_ID, required = false) String headerRunId,
            @RequestHeader(value = CorrelationHeaders.JOURNEY_ID, required = false) String headerJourneyId,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_SCENARIO, required = false) String headerScenario,
            @RequestHeader(value = CorrelationHeaders.SIMULATION_ENABLED, required = false) String headerEnabled
    ) {
        ScenarioContext ctx = resolveContext(headerRunId, headerJourneyId, headerScenario, headerEnabled);
        try {
            TelemetryHelper.bindContext(ctx, JourneyState.STARTED);
            String normalizedEmail = UserRepository.normalizeEmail(request.email());
            String newHash = passwordEncoder.encode(request.newPassword());
            boolean updated = userRepository.updatePasswordByEmail(normalizedEmail, newHash);
            if (!updated) {
                TelemetryHelper.incrementCounter(
                        "commerce.auth.password_resets",
                        "{reset}",
                        "Total password reset attempts in auth-service",
                        Map.of("auth.outcome", "not_found")
                );
                log.warn("Password reset rejected: account not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        ApiErrorResponse.of(
                                HttpStatus.NOT_FOUND.value(),
                                "USER_NOT_FOUND",
                                "No account found with this email address",
                                ctx.runId(),
                                ctx.journeyId(),
                                TelemetryHelper.currentTraceId()
                        )
                );
            }
            TelemetryHelper.incrementCounter(
                    "commerce.auth.password_resets",
                    "{reset}",
                    "Total password reset attempts in auth-service",
                    Map.of("auth.outcome", "success")
            );
            log.info("User password reset completed successfully");
            return ResponseEntity.ok(Map.of(
                    "status", "PASSWORD_UPDATED",
                    "email", normalizedEmail
            ));
        } finally {
            TelemetryHelper.clearMdc();
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        TelemetryHelper.recordError(JourneyState.INTERNAL_FAILURE, "VALIDATION_ERROR", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        "VALIDATION_ERROR",
                        "Invalid authentication request payload",
                        "",
                        "",
                        TelemetryHelper.currentTraceId()
                )
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        TelemetryHelper.recordError(JourneyState.INTERNAL_FAILURE, "INTERNAL_FAILURE", ex);
        log.error("Unexpected auth service error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiErrorResponse.of(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "INTERNAL_FAILURE",
                        "Internal auth service error",
                        "",
                        "",
                        TelemetryHelper.currentTraceId()
                )
        );
    }

    private ScenarioContext resolveContext(String runId, String journeyId, String scenario, String enabled) {
        String effectiveRunId = (runId != null && !runId.isBlank()) ? runId : UUID.randomUUID().toString();
        String effectiveJourneyId = (journeyId != null && !journeyId.isBlank()) ? journeyId : UUID.randomUUID().toString();
        return ScenarioContext.resolve(
                effectiveRunId, effectiveJourneyId, scenario, enabled, defaultScenario, defaultScenarioEnabled
        );
    }
}
