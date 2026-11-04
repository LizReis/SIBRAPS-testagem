package com.pet.buscaativa.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JwtKeyPolicyTests {
    @Test
    void forbiddenFingerprintIsRejectedByTheActualStartupConstructor() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
        var policy = new JwtKeyPolicy(Set.of(fingerprint));
        new ApplicationContextRunner().withBean(TokenServiceImpl.class, () -> new TokenServiceImpl(encoded, policy))
                .run(c -> {
                    assertThat(c).hasFailed();
                    assertThat(c.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
        assertDoesNotThrow(() -> new TokenServiceImpl(encoded));
    }

    @Test
    void productionPolicyCannotBeOverriddenByEnvironmentConfiguration() {
        var policy = JwtKeyPolicy.production();
        assertTrue(policy.revokes("321a61cd161b2e83d848740ec3a1f8168e76aba5a86e813e3c7a849099ff4ba4"));
        assertFalse(policy.revokes("0".repeat(64)));
    }
}
