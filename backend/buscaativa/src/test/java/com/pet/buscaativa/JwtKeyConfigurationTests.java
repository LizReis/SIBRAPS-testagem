package com.pet.buscaativa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.services.impl.TokenServiceImpl;

class JwtKeyConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(TokenServiceImpl.class);

    @Test
    void jwt01MissingKeyFailsStartupWithoutRevealingValues() {
        context.run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasRootCauseMessage(
                    "JWT_SECRET_BASE64 ausente ou vazia. Configure uma chave externa gerada por fonte criptográfica segura; nenhum fallback é permitido.");
        });
    }

    static Stream<String> unsafeKeys() {
        return Stream.of("", "   ", "not-base64!", "%%%", "YWJj", // JWT-02/03/04
                Base64.getEncoder().encodeToString(new byte[32]),
                Base64.getEncoder().encodeToString("change-me-example-key-not-for-production".getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString(java.util.stream.IntStream.range(0, 32)
                        .collect(java.io.ByteArrayOutputStream::new, java.io.ByteArrayOutputStream::write,
                                (a,b) -> a.writeBytes(b.toByteArray())).toByteArray()),
                Base64.getEncoder().encodeToString(SecurityTestSupport.randomBytes()).replace("=", ""));
    }

    @ParameterizedTest(name = "unsafe key case {index}")
    @MethodSource("unsafeKeys")
    void jwt02To04RejectUnsafeKeysAtStartup(String candidate) {
        context.withPropertyValues("JWT_SECRET_BASE64=" + candidate).run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void jwt05DeprecatedPropertyCannotSupplyFallback() {
        context.withPropertyValues("api.security.token.secret=" + SecurityTestSupport.TEST_KEY_BASE64)
                .run(c -> assertThat(c).hasFailed());
    }

    @Test
    void jwt05RejectsActualRetiredMaterialEvenWhenBase64Encoded() {
        byte[] retired = RetiredJwtFixture.key();
        try {
            var exception = assertThrows(IllegalArgumentException.class,
                    () -> new TokenServiceImpl(Base64.getEncoder().encodeToString(retired)));
            assertTrue(exception.getMessage().contains("revogado"));
        } finally {
            java.util.Arrays.fill(retired, (byte) 0);
        }
    }

    @Test
    void acceptsFreshExternalKeyAndNeverAcceptsAnotherKeyAfterRotation() {
        var before = new TokenServiceImpl(SecurityTestSupport.TEST_KEY_BASE64);
        var after = new TokenServiceImpl(Base64.getEncoder().encodeToString(SecurityTestSupport.randomBytes()));
        var user = new Usuario();
        user.setEmail("rotation@test.local");
        String token = before.gerarToken(user);
        assertEquals(user.getEmail(), before.validarToken(token));
        assertEquals("", after.validarToken(token));
    }
}
