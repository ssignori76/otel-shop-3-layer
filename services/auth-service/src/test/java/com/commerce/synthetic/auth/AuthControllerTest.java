package com.commerce.synthetic.auth;

import com.commerce.synthetic.api.AuthResponse;
import com.commerce.synthetic.api.JwtTokenProvider;
import com.commerce.synthetic.api.LoginRequest;
import com.commerce.synthetic.api.RegisterRequest;
import com.commerce.synthetic.api.UserProfileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_test_db;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "commerce.auth.jwt-secret=unit-test-jwt-secret-key-at-least-32-chars",
        "commerce.auth.demo-user.enabled=true"
})
class AuthControllerTest {

    @Autowired
    private AuthController authController;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void demoUserCanLoginAndFetchProfile() {
        ResponseEntity<?> loginResp = authController.login(
                new LoginRequest("demo@otelshop.local", "DemoPass123!"),
                null, null, null, null
        );
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthResponse auth = (AuthResponse) loginResp.getBody();
        assertThat(auth).isNotNull();
        assertThat(auth.tokenType()).isEqualTo("Bearer");
        assertThat(jwtTokenProvider.verifyAndParse(auth.accessToken())).isPresent();

        ResponseEntity<?> meResp = authController.me(auth.user().userId(), null, null, null, null);
        assertThat(meResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        UserProfileResponse profile = (UserProfileResponse) meResp.getBody();
        assertThat(profile).isNotNull();
        assertThat(profile.email()).isEqualTo("demo@otelshop.local");
    }

    @Test
    void registerNewUserAndRejectDuplicateAndWrongPassword() {
        ResponseEntity<?> regResp = authController.register(
                new RegisterRequest("alice@example.com", "Alice Tester", "StrongPass123!"),
                null, null, null, null
        );
        assertThat(regResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<?> dupResp = authController.register(
                new RegisterRequest("ALICE@example.com", "Alice Duplicate", "StrongPass123!"),
                null, null, null, null
        );
        assertThat(dupResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<?> badLogin = authController.login(
                new LoginRequest("alice@example.com", "WrongPass999!"),
                null, null, null, null
        );
        assertThat(badLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
