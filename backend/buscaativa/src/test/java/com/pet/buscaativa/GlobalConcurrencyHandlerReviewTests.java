package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionSystemException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.*;
import com.pet.buscaativa.services.exceptions.*;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import com.pet.buscaativa.services.impl.UserDetailsServiceImpl;
import tools.jackson.databind.ObjectMapper;

/** Fault injection at service boundaries characterizes HTTP mapping, not real DB races in every module. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:global-handler-review;DB_CLOSE_DELAY=-1",
        "server.error.include-message=never"})
class GlobalConcurrencyHandlerReviewTests extends SecurityTestSupport {
    enum Module { PACIENTES, GRUPOS, PROFISSIONAIS, DISPONIBILIDADE, HISTORICO, RELATORIOS }
    enum Failure {
        SPRING_OPTIMISTIC("STALE_VERSION"), SPRING_LOCK("CONCURRENT_UPDATE"),
        DATABASE_DOWN("DATABASE_CONNECTION_FAILED"), JPA_OPTIMISTIC("STALE_VERSION"),
        JPA_PESSIMISTIC("CONCURRENT_UPDATE"), JPA_LOCK_TIMEOUT("LOCK_ACQUISITION_TIMEOUT"),
        QUERY_TIMEOUT("DATABASE_QUERY_TIMEOUT"), PROGRAMMING("INTERNAL_ERROR"), BUSINESS("BUSINESS_CONFLICT"),
        VALIDATION("VALIDATION_FAILED"), NESTED_DEADLOCK("CONCURRENT_DEADLOCK"),
        NESTED_DRIVER_LOCK_TIMEOUT("LOCK_ACQUISITION_TIMEOUT"), NESTED_QUERY_TIMEOUT("DATABASE_QUERY_TIMEOUT"),
        NESTED_CONNECTION("DATABASE_CONNECTION_FAILED"), NESTED_TEMPORARY_OUTAGE("SERVICE_UNAVAILABLE"),
        NESTED_INTEGRITY("DATABASE_INTEGRITY_ERROR"), NESTED_SQL_SYNTAX("DATABASE_ERROR"),
        REPORT_RENDERER("REPORT_GENERATION_FAILED"), NESTED_PROGRAMMING("INTERNAL_ERROR"),
        NESTED_CONNECTION_TIMEOUT("DATABASE_CONNECTION_TIMEOUT");

        final String code;

        Failure(String code) {
            this.code = code;
        }
    }

    private static final String INTERNAL_DETAIL = "synthetic technical SQL and credential marker";
    @Value("${local.server.port}") int port;
    @MockitoBean SusApiAdapter sus;
    @MockitoBean PacienteService patients;
    @MockitoBean GrupoTerapeuticoService groups;
    @MockitoBean UsuarioService professionals;
    @MockitoBean DisponibilidadeService availability;
    @MockitoBean HistoricoPacienteService histories;
    @MockitoBean RelatorioService reports;
    @Autowired UsuarioRepository users;
    @MockitoSpyBean UserDetailsServiceImpl userDetails;
    @Autowired TokenService tokens;
    @Autowired ObjectMapper json;
    private String token;
    private String email;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach void prepare() {
        reset(patients, groups, professionals, availability, histories, reports);
        var user = new Usuario(); user.setNome("Synthetic handler review");
        user.setEmail(UUID.randomUUID() + "@test.local"); user.setSenha("not-used-for-login");
        user.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD); user.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        token = tokens.gerarToken(users.saveAndFlush(user));
        email = user.getEmail();
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
                Arguments.of(Module.PACIENTES, Failure.VALIDATION, 422),
                Arguments.of(Module.PACIENTES, Failure.NESTED_DEADLOCK, 409),
                Arguments.of(Module.PACIENTES, Failure.NESTED_DRIVER_LOCK_TIMEOUT, 409),
                Arguments.of(Module.PACIENTES, Failure.NESTED_QUERY_TIMEOUT, 500),
                Arguments.of(Module.PACIENTES, Failure.NESTED_CONNECTION, 500),
                Arguments.of(Module.PACIENTES, Failure.NESTED_CONNECTION_TIMEOUT, 500),
                Arguments.of(Module.PACIENTES, Failure.NESTED_INTEGRITY, 500),
                Arguments.of(Module.HISTORICO, Failure.NESTED_SQL_SYNTAX, 500),
                Arguments.of(Module.RELATORIOS, Failure.NESTED_TEMPORARY_OUTAGE, 503),
                Arguments.of(Module.RELATORIOS, Failure.REPORT_RENDERER, 500),
                Arguments.of(Module.PACIENTES, Failure.NESTED_PROGRAMMING, 500));
    }

    private RuntimeException exception(Failure failure) {
        return switch (failure) {
            case SPRING_OPTIMISTIC -> new org.springframework.orm.ObjectOptimisticLockingFailureException("SyntheticEntity", 1L);
            case SPRING_LOCK -> new org.springframework.dao.CannotAcquireLockException(INTERNAL_DETAIL);
            case DATABASE_DOWN -> new org.springframework.jdbc.CannotGetJdbcConnectionException(INTERNAL_DETAIL);
            case JPA_OPTIMISTIC -> new jakarta.persistence.OptimisticLockException(INTERNAL_DETAIL);
            case JPA_PESSIMISTIC -> new jakarta.persistence.PessimisticLockException(INTERNAL_DETAIL);
            case JPA_LOCK_TIMEOUT -> new jakarta.persistence.LockTimeoutException(INTERNAL_DETAIL);
            case QUERY_TIMEOUT -> new org.springframework.dao.QueryTimeoutException(INTERNAL_DETAIL);
            case PROGRAMMING -> new IllegalStateException(INTERNAL_DETAIL);
            case BUSINESS -> new ConflictException("synthetic business conflict");
            case VALIDATION -> new ValidationException("synthetic validation rejection");
            case NESTED_DEADLOCK -> {
                var batchError = new SQLException(INTERNAL_DETAIL);
                batchError.setNextException(new SQLException(INTERNAL_DETAIL, "40001", 40001));
                yield new TransactionSystemException(INTERNAL_DETAIL, batchError);
            }
            case NESTED_DRIVER_LOCK_TIMEOUT -> new TransactionSystemException(INTERNAL_DETAIL,
                    new SQLTimeoutException(INTERNAL_DETAIL, "HYT00", 50200));
            case NESTED_QUERY_TIMEOUT -> new TransactionSystemException(INTERNAL_DETAIL,
                    new org.springframework.dao.CannotAcquireLockException(INTERNAL_DETAIL,
                            new SQLTimeoutException(INTERNAL_DETAIL, "HYT00")));
            case NESTED_CONNECTION -> new TransactionSystemException(INTERNAL_DETAIL,
                    new org.springframework.dao.CannotAcquireLockException(INTERNAL_DETAIL,
                            new SQLNonTransientConnectionException(INTERNAL_DETAIL, "08001")));
            case NESTED_CONNECTION_TIMEOUT -> new TransactionSystemException(INTERNAL_DETAIL,
                    new SQLTimeoutException(INTERNAL_DETAIL, "HYT01"));
            case NESTED_TEMPORARY_OUTAGE -> new RelatorioException(INTERNAL_DETAIL,
                    new TransactionSystemException(INTERNAL_DETAIL,
                            new SQLTransientConnectionException(INTERNAL_DETAIL, "08006")));
            case NESTED_INTEGRITY -> new TransactionSystemException(INTERNAL_DETAIL,
                    new SQLException(INTERNAL_DETAIL, "23503"));
            case NESTED_SQL_SYNTAX -> new TransactionSystemException(INTERNAL_DETAIL,
                    new SQLException(INTERNAL_DETAIL, "42000"));
            case REPORT_RENDERER -> new RelatorioException(INTERNAL_DETAIL, new IllegalStateException(INTERNAL_DETAIL));
            case NESTED_PROGRAMMING -> new TransactionSystemException(INTERNAL_DETAIL, new IllegalStateException(INTERNAL_DETAIL));
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

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(10));
    }

    private void verifyMapping(Module module, Failure failure, int expected) throws Exception {
        String path = inject(module, exception(failure));
        var response = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertError(response, expected, failure.code, path);
        var body = json.readTree(response.body());
        if (failure == Failure.BUSINESS) assertEquals("synthetic business conflict", body.get("message").asString());
        if (failure == Failure.VALIDATION) assertEquals("synthetic validation rejection", body.get("message").asString());
        if ("STALE_VERSION".equals(failure.code)) assertTrue(body.get("message").asString().contains("Atualize"));
    }

    private String assertError(HttpResponse<String> response, int status, String code, String path) {
        assertEquals(status, response.statusCode());
        var body = json.readTree(response.body());
        assertEquals(status, body.get("status").asInt());
        assertEquals(code, body.get("code").asString());
        assertEquals(path, body.get("path").asString());
        assertFalse(body.get("error").asString().isBlank());
        assertFalse(body.get("message").asString().isBlank());
        assertNotNull(body.get("timestamp"));
        assertFalse(response.body().contains(INTERNAL_DETAIL));
        assertFalse(response.body().contains("SyntheticEntity"));
        String id = body.get("correlationId").asString();
        assertEquals(id, UUID.fromString(id).toString());
        assertEquals(id, response.headers().firstValue(ApiErrorResponses.CORRELATION_HEADER).orElseThrow());
        return id;
    }

    @Test
    void technicalDiagnosisUsesResponseCorrelationWithoutLoggingRawExceptionContent() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(ResourceExceptionHandler.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            String path = inject(Module.PACIENTES, exception(Failure.NESTED_SQL_SYNTAX));
            var response = client.send(request(path + "?ignored=" + UUID.randomUUID()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String id = assertError(response, 500, "DATABASE_ERROR", path);
            var entry = captured.list.stream().filter(e -> e.getFormattedMessage().contains(id)).findFirst().orElseThrow();
            assertTrue(entry.getFormattedMessage().contains("TransactionSystemException"));
            assertTrue(entry.getFormattedMessage().contains("java.sql.SQLException [state=42000"));
            assertTrue(entry.getFormattedMessage().contains("route=/api/pacientes"));
            assertFalse(entry.getFormattedMessage().contains(INTERNAL_DETAIL));
            assertFalse(entry.getFormattedMessage().contains("?ignored="));
            assertNull(entry.getThrowableProxy());
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void databaseOutageDuringTokenUserLookupReturnsTechnicalError() throws Exception {
        doThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException(INTERNAL_DETAIL,
                new SQLTransientConnectionException(INTERNAL_DETAIL, "08006"))).when(userDetails).loadUserByUsername(email);
        String path = "/api/pacientes";
        var response = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertError(response, 503, "SERVICE_UNAVAILABLE", path);
        verifyNoInteractions(patients);
    }

    @Test
    void beanValidationPreservesIndividualFieldsAndRejectsBeforeServiceCall() throws Exception {
        String path = "/api/pacientes";
        var response = client.send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"situacaoRua\":true}")).build(), HttpResponse.BodyHandlers.ofString());
        assertError(response, 422, "VALIDATION_FAILED", path);
        var errors = json.readTree(response.body()).get("errors");
        assertTrue(errors.isArray());
        assertTrue(java.util.stream.StreamSupport.stream(errors.spliterator(), false)
                .anyMatch(field -> "nome".equals(field.get("fieldName").asString())
                        && !field.get("message").asString().isBlank()));
        verifyNoInteractions(patients);
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
