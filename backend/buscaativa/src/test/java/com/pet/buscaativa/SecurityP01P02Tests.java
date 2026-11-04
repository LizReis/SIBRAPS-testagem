package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.TokenService;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class SecurityP01P02Tests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;
    @Autowired MockMvc mvc;
    @Autowired UsuarioRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired TokenService tokens;
    @Autowired ObjectMapper json;
    Usuario admin, professional;
    private String password;

    @BeforeEach
    void fixtures() {
        password = UUID.randomUUID().toString();
        admin = user(TipoUsuario.ADMINISTRADOR);
        professional = user(TipoUsuario.PROFISSIONAL);
    }

    private Usuario user(TipoUsuario role) {
        var u = new Usuario();
        u.setNome("Regressão segurança");
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha(encoder.encode(password));
        u.setTipoUsuario(role);
        u.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        return users.saveAndFlush(u);
    }

    private String signed(byte[] key, Instant expires, String issuer) {
        return JWT.create().withIssuer(issuer).withSubject(admin.getEmail())
                .withExpiresAt(expires).sign(Algorithm.HMAC256(key));
    }

    private void assertUnauthorized(String token) throws Exception {
        var request = get("/api/usuarios/me");
        if (token != null) request.header("Authorization", "Bearer " + token);
        assertEquals(401, mvc.perform(request).andReturn().getResponse().getStatus());
    }

    @Test
    void jwt06And07LoginContractAndAuthenticatedRequest() throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("email", admin.getEmail(), "senha", password));
        var response = mvc.perform(post("/api/auth/login").contentType("application/json").content(body))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var result = json.readTree(response.getContentAsString());
        assertEquals("Bearer", result.get("tipo").asString());
        String token = result.get("token").asString();
        assertEquals(admin.getEmail(), tokens.validarToken(token));
        var me = mvc.perform(get("/api/usuarios/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertEquals(200, me.getStatus());
        assertEquals(admin.getEmail(), json.readTree(me.getContentAsString()).get("email").asString());
    }

    @Test void jwt08IncorrectSignature() throws Exception {
        assertUnauthorized(signed(randomBytes(), Instant.now().plusSeconds(120), "buscaativa-api"));
    }
    @Test void jwt09Expired() throws Exception {
        assertUnauthorized(signed(TEST_KEY, Instant.now().minusSeconds(120), "buscaativa-api"));
    }
    @Test void jwt10IncorrectIssuer() throws Exception {
        assertUnauthorized(signed(TEST_KEY, Instant.now().plusSeconds(120), "incorrect"));
    }
    @Test void jwt11TamperedPayload() throws Exception {
        String token = tokens.gerarToken(admin);
        String[] parts = token.split("\\.");
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"buscaativa-api\",\"sub\":\"tampered@test.local\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertUnauthorized(parts[0] + "." + payload + "." + parts[2]);
    }
    @Test void jwt12NoToken() throws Exception { assertUnauthorized(null); }
    @Test void jwt13ProfessionalCannotManageUsers() throws Exception {
        assertEquals(403, mvc.perform(get("/api/usuarios")
                .header("Authorization", "Bearer " + tokens.gerarToken(professional)))
                .andReturn().getResponse().getStatus());
    }
    @Test void jwt14PreviousSigningKeyHasNoFallback() throws Exception {
        var previous = new com.pet.buscaativa.services.impl.TokenServiceImpl(
                Base64.getEncoder().encodeToString(randomBytes()));
        assertUnauthorized(previous.gerarToken(admin));
    }

    @Test void jwt14ActualCompromisedKeyCannotImpersonateAdmin() throws Exception {
        byte[] retired = RetiredJwtFixture.key();
        try {
            assertUnauthorized(signed(retired, Instant.now().plusSeconds(120), "buscaativa-api"));
        } finally {
            java.util.Arrays.fill(retired, (byte) 0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/h2-console", "/h2-console/", "/h2-console/login.do", "/h2-console/query.do", "/internal-db/"})
    void h201To04And10NoConsoleForAnonymousProfessionalOrAdmin(String path) throws Exception {
        for (String token : new String[] {"", tokens.gerarToken(professional), tokens.gerarToken(admin)}) {
            for (var request : java.util.List.of(get(path), post(path).contentType("application/x-www-form-urlencoded")
                    .content("driver=org.h2.Driver&url=jdbc:h2:mem:sibraps-tests&user=sa&password="))) {
                if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
                int status = mvc.perform(request).andReturn().getResponse().getStatus();
                assertTrue(status == 401 || status == 403 || status == 404, "Console must remain inaccessible");
            }
        }
    }

    @Test
    void h208And09DatabaseAndScopedApiStillWork() throws Exception {
        assertTrue(users.findByIdPublico(admin.getIdPublico()).isPresent());
        var other = user(TipoUsuario.PROFISSIONAL);
        other.setUnidadeAtuacao(UnidadeAtuacao.CAPS_I);
        users.saveAndFlush(other);
        var response = mvc.perform(get("/api/usuarios/" + other.getIdPublico())
                .header("Authorization", "Bearer " + tokens.gerarToken(admin))).andReturn().getResponse();
        assertEquals(404, response.getStatus());
    }
}
