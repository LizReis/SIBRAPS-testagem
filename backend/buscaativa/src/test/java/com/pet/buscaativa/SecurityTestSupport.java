package com.pet.buscaativa;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Synthetic material confined to the test classpath; no application fallback. */
@org.springframework.test.context.ActiveProfiles("test")
abstract class SecurityTestSupport {
    protected static final byte[] TEST_KEY = randomBytes();
    protected static final String TEST_KEY_BASE64 = Base64.getEncoder().encodeToString(TEST_KEY);
    private static final String TEST_DB_PASSWORD = Base64.getEncoder().encodeToString(randomBytes());

    static byte[] randomBytes() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET_BASE64", () -> TEST_KEY_BASE64);
        registry.add("spring.datasource.password", () -> TEST_DB_PASSWORD);
    }
}
