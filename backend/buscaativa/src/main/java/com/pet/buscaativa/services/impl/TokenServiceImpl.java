package com.pet.buscaativa.services.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.services.TokenService;

@Service
public class TokenServiceImpl implements TokenService {

    // Fingerprint only: never retain or accept the retired signing material.
    private static final String RETIRED_KEY_SHA256 = "321a61cd161b2e83d848740ec3a1f8168e76aba5a86e813e3c7a849099ff4ba4";
    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    public TokenServiceImpl(@Value("${JWT_SECRET_BASE64:}") String encodedSecret) {
        byte[] key = decodeKey(encodedSecret);
        try {
            this.algorithm = Algorithm.HMAC256(key);
            this.verifier = JWT.require(algorithm).withIssuer("buscaativa-api").build();
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw invalidKey("ausente ou vazia");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw invalidKey("Base64 inválido");
        }
        // Require canonical Base64; do not silently accept whitespace or omitted padding.
        if (!Base64.getEncoder().encodeToString(key).equals(encoded) || key.length < 32) {
            Arrays.fill(key, (byte) 0);
            throw invalidKey("use Base64 canônico de pelo menos 32 bytes aleatórios");
        }
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
            String text = new String(key, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            long distinctBytes = java.util.stream.IntStream.range(0, key.length).map(i -> key[i] & 255).distinct().count();
            boolean sequential = true;
            for (int i = 1; i < key.length; i++) {
                if ((key[i] & 255) != ((key[i - 1] & 255) + 1) % 256) sequential = false;
            }
            if (RETIRED_KEY_SHA256.equals(fingerprint) || distinctBytes < 16 || sequential
                    || text.matches("(?s).*(example|exemplo|changeme|changeit|password|secret|replace|test-only|sua-chave|your-key).*")) {
                Arrays.fill(key, (byte) 0);
                throw invalidKey("material revogado, previsível ou de exemplo");
            }
            return key;
        } catch (NoSuchAlgorithmException exception) {
            Arrays.fill(key, (byte) 0);
            throw new IllegalStateException("SHA-256 indisponível para validar a configuração JWT.");
        }
    }

    private static IllegalArgumentException invalidKey(String reason) {
        return new IllegalArgumentException("JWT_SECRET_BASE64 " + reason
                + ". Configure uma chave externa gerada por fonte criptográfica segura; nenhum fallback é permitido.");
    }

    public String gerarToken(Usuario usuario) {
        try {
            return JWT.create().withIssuer("buscaativa-api").withSubject(usuario.getEmail()).withExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS)).sign(algorithm);

        } catch (JWTCreationException exception) {
            throw new RuntimeException("Erro ao gerar token JWT", exception);
        }
    }

    public String validarToken(String token) {
        try {
            return verifier.verify(token).getSubject();
        } catch (JWTVerificationException exception) {
            return "";
        }
    }
}
