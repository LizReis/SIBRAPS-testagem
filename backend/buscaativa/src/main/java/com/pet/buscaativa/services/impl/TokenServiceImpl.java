package com.pet.buscaativa.services.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

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

    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    @org.springframework.beans.factory.annotation.Autowired
    public TokenServiceImpl(@Value("${JWT_SECRET_BASE64:}") String encodedSecret) {
        this(encodedSecret, JwtKeyPolicy.production());
    }

    TokenServiceImpl(String encodedSecret, JwtKeyPolicy policy) {
        byte[] key = policy.decodeKey(encodedSecret);
        try {
            this.algorithm = Algorithm.HMAC256(key);
            this.verifier = JWT.require(algorithm).withIssuer("buscaativa-api")
                    .withClaimPresence("exp").withClaimPresence("uid").build();
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public String gerarToken(Usuario usuario) {
        try {
            return JWT.create().withIssuer("buscaativa-api").withSubject(usuario.getEmail())
                    .withClaim("uid", usuario.getIdPublico().toString())
                    .withExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS)).sign(algorithm);

        } catch (JWTCreationException exception) {
            throw new RuntimeException("Erro ao gerar token JWT", exception);
        }
    }

    @Override
    public Optional<Identidade> verificarIdentidade(String token) {
        try {
            var decoded = verifier.verify(token);
            String email = decoded.getSubject();
            String uid = decoded.getClaim("uid").asString();
            if (email == null || email.isBlank() || uid == null || decoded.getExpiresAt() == null) return Optional.empty();
            return Optional.of(new Identidade(email, UUID.fromString(uid)));
        } catch (JWTVerificationException | IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
