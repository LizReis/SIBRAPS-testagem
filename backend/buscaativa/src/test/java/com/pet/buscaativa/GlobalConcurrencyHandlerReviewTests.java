package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.*;
import com.pet.buscaativa.services.exceptions.*;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import tools.jackson.databind.ObjectMapper;

/** Fault injection at service boundaries characterizes HTTP mapping, not real DB races in every module. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:global-handler-review;DB_CLOSE_DELAY=-1",
        "server.error.include-message=never"})
class GlobalConcurrencyHandlerReviewTests extends SecurityTestSupport {
    enum Module { PACIENTES, GRUPOS, PROFISSIONAIS, DISPONIBILIDADE, HISTORICO, RELATORIOS }
    enum Failure { SPRING_OPTIMISTIC, SPRING_LOCK, DATABASE_DOWN, JPA_OPTIMISTIC, JPA_PESSIMISTIC,
        JPA_LOCK_TIMEOUT, QUERY_TIMEOUT, PROGRAMMING, BUSINESS, VALIDATION }
    @Value("${local.server.port}") int port;
    @MockitoBean SusApiAdapter sus;
    @MockitoBean PacienteService patients;
    @MockitoBean GrupoTerapeuticoService groups;
    @MockitoBean UsuarioService professionals;
    @MockitoBean DisponibilidadeService availability;
    @MockitoBean HistoricoPacienteService histories;
    @MockitoBean RelatorioService reports;
    @Autowired UsuarioRepository users;
    @Autowired TokenService tokens;
    @Autowired ObjectMapper json;
    private String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach void prepare() {
        reset(patients, groups, professionals, availability, histories, reports);
        var user = new Usuario(); user.setNome("Synthetic handler review");
        user.setEmail(UUID.randomUUID() + "@test.local"); user.setSenha("not-used-for-login");
        user.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD); user.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        token = tokens.gerarToken(users.saveAndFlush(user));
    }

    static Stream<Arguments> moduleMatrix() {
        return Stream.of(Module.values()).flatMap(module -> Stream.of(
                Arguments.of(module, Failure.SPRING_OPTIMISTIC, 409),
                Arguments.of(module, Failure.SPRING_LOCK, 409),
                Arguments.of(module, Failure.DATABASE_DOWN, 500)));
    }

    static Stream<Arguments> classificationMatrix() {
        return Stream.of(
                Arguments.of(Module.PACIENTES, Failure.JPA_OPTIMISTIC, 409),
                Arguments.of(Module.PACIENTES, Failure.JPA_PESSIMISTIC, 409),
                Arguments.of(Module.PACIENTES, Failure.JPA_LOCK_TIMEOUT, 409),
                Arguments.of(Module.PACIENTES, Failure.QUERY_TIMEOUT, 500),
                Arguments.of(Module.PACIENTES, Failure.PROGRAMMING, 500),
                Arguments.of(Module.PACIENTES, Failure.BUSINESS, 409),
                Arguments.of(Module.PACIENTES, Failure.VALIDATION, 422));
    }

    private RuntimeException exception(Failure failure) {
        return switch (failure) {
            case SPRING_OPTIMISTIC -> new org.springframework.orm.ObjectOptimisticLockingFailureException("SyntheticEntity", 1L);
            case SPRING_LOCK -> new org.springframework.dao.CannotAcquireLockException("synthetic internal diagnostic");
            case DATABASE_DOWN -> new org.springframework.jdbc.CannotGetJdbcConnectionException("synthetic internal diagnostic");
            case JPA_OPTIMISTIC -> new jakarta.persistence.OptimisticLockException("synthetic version conflict");
            case JPA_PESSIMISTIC -> new jakarta.persistence.PessimisticLockException("synthetic internal diagnostic");
            case JPA_LOCK_TIMEOUT -> new jakarta.persistence.LockTimeoutException("synthetic internal diagnostic");
            case QUERY_TIMEOUT -> new org.springframework.dao.QueryTimeoutException("synthetic internal diagnostic");
            case PROGRAMMING -> new IllegalStateException("synthetic internal diagnostic");
            case BUSINESS -> new ConflictException("synthetic business conflict");
            case VALIDATION -> new ValidationException("synthetic validation rejection");
        };
    }

    private String inject(Module module, RuntimeException failure) {
        return switch (module) {
            case PACIENTES -> { when(patients.findAll()).thenThrow(failure); yield "/api/pacientes"; }
            case GRUPOS -> { when(groups.listarGrupos()).thenThrow(failure); yield "/api/grupos"; }
            case PROFISSIONAIS -> { when(professionals.findAll()).thenThrow(failure); yield "/api/usuarios"; }
            case DISPONIBILIDADE -> {
                when(availability.listarDisponibilidades(anyString(), nullable(UUID.class))).thenThrow(failure);
                yield "/api/disponibilidade-config/disponibilidade";
            }
            case HISTORICO -> {
                when(histories.consultar(any(UUID.class))).thenThrow(failure);
                yield "/api/pacientes/" + UUID.randomUUID() + "/historico";
            }
            case RELATORIOS -> {
                when(reports.gerarBuscaAtiva(any(), any(), any(), any())).thenThrow(failure);
                yield "/api/relatorios/busca-ativa/pdf";
            }
        };
    }

    private void verifyMapping(Module module, Failure failure, int expected) throws Exception {
        String path = inject(module, exception(failure));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(10)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.printf("HANDLER_REVIEW module=%s failure=%s status=%d%n", module, failure, response.statusCode());
        assertEquals(expected, response.statusCode());
        assertEquals(expected, json.readTree(response.body()).get("status").asInt());
        if (failure == Failure.SPRING_OPTIMISTIC || failure == Failure.SPRING_LOCK
                || failure == Failure.JPA_PESSIMISTIC || failure == Failure.JPA_LOCK_TIMEOUT) {
            assertFalse(response.body().contains("synthetic internal diagnostic"));
        }
    }

    @ParameterizedTest(name = "HTTP module={0}, failure={1}") @MethodSource("moduleMatrix")
    void globalHandlerAffectsControllersOutsideAppointments(Module module, Failure failure, int expected) throws Exception {
        verifyMapping(module, failure, expected);
    }

    @ParameterizedTest(name = "HTTP classification={1}") @MethodSource("classificationMatrix")
    void infrastructureAndProgrammingFailuresAreNotAutomaticallyBusinessConflicts(Module module, Failure failure, int expected) throws Exception {
        verifyMapping(module, failure, expected);
    }
}
