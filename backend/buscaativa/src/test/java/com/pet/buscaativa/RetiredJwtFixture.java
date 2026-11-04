package com.pet.buscaativa;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Reads compromised historical material in memory, without republishing a key or token. */
final class RetiredJwtFixture {
    private RetiredJwtFixture() {}

    static byte[] key() {
        try {
            Process process = new ProcessBuilder("git", "show",
                    "e92606fb463dc7880684ab193b5a9dd543e0efed:backend/buscaativa/src/main/resources/application-dev.properties")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] output = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) throw new IllegalStateException("Histórico local necessário para regressão da chave revogada.");
            try {
                String config = new String(output, StandardCharsets.UTF_8);
                return config.lines().filter(line -> line.startsWith("api.security.token.secret="))
                        .map(line -> line.substring(line.indexOf('=') + 1).getBytes(StandardCharsets.UTF_8))
                        .findFirst().orElseThrow(() -> new IllegalStateException("Fixture histórica indisponível."));
            } finally {
                Arrays.fill(output, (byte) 0);
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Git local necessário para regressão da chave revogada.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Leitura da fixture histórica interrompida.");
        }
    }
}
