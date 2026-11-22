package com.commerce.synthetic.auth;

import com.commerce.synthetic.api.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AuthConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public JwtTokenProvider jwtTokenProvider(
            @Value("${commerce.auth.jwt-secret}") String jwtSecret,
            @Value("${commerce.auth.jwt-expiration-seconds:86400}") long expirationSeconds,
            ObjectMapper objectMapper
    ) {
        return new JwtTokenProvider(jwtSecret, expirationSeconds, objectMapper);
    }
}
