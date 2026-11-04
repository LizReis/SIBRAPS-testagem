package com.pet.buscaativa.services.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

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
            this.verifier = JWT.require(algorithm).withIssuer("buscaativa-api").build();
        } finally {
            Arrays.fill(key, (byte) 0);
        }
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
