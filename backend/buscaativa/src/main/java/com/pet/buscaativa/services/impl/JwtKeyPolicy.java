package com.pet.buscaativa.services.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** Immutable policy: production revocations cannot be overridden by environment properties. */
public final class JwtKeyPolicy {
    // Fingerprint only: never retain or accept the retired signing material.
    private static final String RETIRED_KEY_SHA256 = "321a61cd161b2e83d848740ec3a1f8168e76aba5a86e813e3c7a849099ff4ba4";
    private final Set<String> revoked;

    private JwtKeyPolicy() { this(Set.of(RETIRED_KEY_SHA256)); }

    // Package-private seam for synthetic regression tests; not a Spring configuration property.
    JwtKeyPolicy(Set<String> revoked) { this.revoked = Set.copyOf(revoked); }

    public static JwtKeyPolicy production() { return new JwtKeyPolicy(); }

    public boolean revokes(String fingerprint) { return revoked.contains(fingerprint); }

    byte[] decodeKey(String encoded) {
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
            if (revokes(fingerprint) || distinctBytes < 16 || sequential
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

}
