package com.commerce.synthetic.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the predefined demo shopper account idempotently on startup.
 */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String demoEmail;
    private final String demoFullName;
    private final String demoPassword;

    public DemoUserSeeder(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${commerce.auth.demo-user.enabled:true}") boolean enabled,
            @Value("${commerce.auth.demo-user.email:demo@otelshop.local}") String demoEmail,
            @Value("${commerce.auth.demo-user.full-name:Demo Shopper}") String demoFullName,
            @Value("${commerce.auth.demo-user.password:DemoPass123!}") String demoPassword
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.demoEmail = demoEmail;
        this.demoFullName = demoFullName;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (userRepository.findByEmail(demoEmail).isPresent()) {
            log.info("Demo user already exists: email={}", UserRepository.normalizeEmail(demoEmail));
            return;
        }
        try {
            String hash = passwordEncoder.encode(demoPassword);
            UserRecord created = userRepository.createUser(demoEmail, demoFullName, hash);
            log.info("Seeded demo user account: user_id={} email={}", created.userId(), created.email());
        } catch (DuplicateKeyException ignored) {
            log.info("Demo user already seeded concurrently: email={}", UserRepository.normalizeEmail(demoEmail));
        }
    }
}
