package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.AgendamentoDTO;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.*;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import tools.jackson.databind.ObjectMapper;

/** Real H2, independent committed fixtures; concurrent operations never share a test transaction. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agenda-integrity;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.hikari.maximum-pool-size=16"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
abstract class AgendaIntegritySupport extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;
    @Autowired UsuarioRepository users;
    @Autowired PacienteRepository patients;
    @Autowired AgendamentoRepository appointments;
    @Autowired HistoricoPacienteRepository history;
    @Autowired DisponibilidadeRepository availability;
    @Autowired DisponibilidadeExcecaoRepository exceptions;
    @Autowired BloqueioAgendaRepository blocks;
    @Autowired AgendamentoService service;
    @Autowired TokenService tokens;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    Usuario admin, professional, colleague;
    Paciente patient, other;
    LocalDate date;

    <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s -> action.get()); }

    @BeforeEach
    void prepareAgenda() {
        date = LocalDate.now().plusDays(30);
        tx(() -> {
            admin = user(TipoUsuario.ADMINISTRADOR, UnidadeAtuacao.CAPS_AD);
            professional = user(TipoUsuario.PROFISSIONAL, UnidadeAtuacao.CAPS_AD);
            colleague = user(TipoUsuario.PROFISSIONAL, UnidadeAtuacao.CAPS_AD);
            patient = patient(CapsEnum.CAPS_AD);
            other = patient(CapsEnum.CAPS_AD);
            for (Usuario u : List.of(professional, colleague)) {
                capacity(u, date, TurnoEnum.MANHA, 10);
                capacity(u, date, TurnoEnum.TARDE, 12);
            }
            return null;
        });
        authenticate();
    }

    Usuario user(TipoUsuario role, UnidadeAtuacao unit) {
        var u = new Usuario(); u.setNome("Agenda sintética"); u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("not-used-for-login"); u.setTipoUsuario(role); u.setUnidadeAtuacao(unit);
        return users.saveAndFlush(u);
    }

    Paciente patient(CapsEnum unit) {
        var p = new Paciente(); p.setNome("Paciente sintético " + UUID.randomUUID());
        p.setNomeMae("Mãe sintética"); p.setDataNascimento(LocalDate.of(1990, 1, 1));
        p.setCapsReferencia(unit); p.setTipoAcompanhamento(TipoAcompanhamento.INDIVIDUAL);
        p.setStatusPaciente(StatusPaciente.ATIVO);
        return patients.saveAndFlush(p);
    }

    void capacity(Usuario user, LocalDate day, TurnoEnum shift, int total) {
        var d = new DisponibilidadeExcecao(); d.setUsuario(user); d.setData(day); d.setTurno(shift);
        d.setCapacidade(total); exceptions.saveAndFlush(d);
    }

    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    AgendamentoDTO dto(Paciente p, Usuario u, String time, TurnoEnum shift, Long original) {
        return new AgendamentoDTO(null, u.getIdPublico(), null, p.getIdPublico(), null, null,
                date, shift, LocalTime.parse(time), null, original, null);
    }
    AgendamentoDTO dto(String time) { return dto(patient, professional, time, TurnoEnum.MANHA, null); }

    int post(AgendamentoDTO dto) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos")
                .header("Authorization", "Bearer " + tokens.gerarToken(admin))
                .contentType("application/json").content(json.writeValueAsString(dto)))
                .andReturn().getResponse().getStatus();
    }

    AgendamentoDTO create(String time) { return service.save(dto(time)); }
    Agendamento appointment(Long id) { return appointments.findById(id).orElseThrow(); }

    Agendamento seed(Paciente p, Usuario u, SituacaoAtendimento status, LocalDate day, String time) {
        var a = new Agendamento(); a.setPaciente(p); a.setUsuario(u); a.setDataAgendamento(day);
        a.setHoraAtendimento(LocalTime.parse(time)); a.setTurnoAgendamento(TurnoEnum.MANHA);
        a.setSituacaoAtendimento(status); return appointments.saveAndFlush(a);
    }

    record Snapshot(long appointments, long history, int absences, LocalDate lastAttendance,
                    ClassificacaoRisco risk, Boolean visit, SituacaoAtendimento originalStatus, Integer version) {}
    Snapshot snapshot(Paciente p, Long original) {
        return tx(() -> {
            var fresh = patients.findById(p.getId()).orElseThrow();
            var a = original == null ? null : appointment(original);
            return new Snapshot(appointments.count(), history.count(), fresh.getCountFaltas(), fresh.getDataUltimaPresenca(),
                    fresh.getClassificacaoRisco(), fresh.getGatilhoVisitaAcionado(),
                    a == null ? null : a.getSituacaoAtendimento(), a == null ? null : a.getVersion());
        });
    }

    long active(Paciente p, String time) {
        return tx(() -> appointments.findAll().stream().filter(a -> a.getPaciente().getId().equals(p.getId())
                && a.getDataAgendamento().equals(date) && a.getHoraAtendimento().equals(LocalTime.parse(time))
                && List.of(SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO, SituacaoAtendimento.PRESENTE)
                        .contains(a.getSituacaoAtendimento())).count());
    }

    record RaceResult(int confirmed, int rejected) {}
    RaceResult race(String label, List<Callable<Integer>> operations) throws Exception {
        long rowsBefore = appointments.count(), historyBefore = history.count();
        var executor = Executors.newFixedThreadPool(operations.size());
        var ready = new CountDownLatch(operations.size()); var start = new CountDownLatch(1);
        var sessions = ConcurrentHashMap.<Integer>newKeySet();
        try {
            var futures = operations.stream().map(op -> executor.submit(() -> {
                var transaction = new TransactionTemplate(manager);
                transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                return transaction.execute(status -> {
                    sessions.add(jdbc.queryForObject("SELECT SESSION_ID()", Integer.class));
                    ready.countDown(); authenticate();
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        int code = op.call();
                        if (code >= 400) status.setRollbackOnly();
                        return code;
                    } catch (Exception exception) {
                        throw new IllegalStateException("Concurrent operation failed unexpectedly", exception);
                    } finally { SecurityContextHolder.clearContext(); }
                });
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            int ok = 0, rejected = 0;
            for (var f : futures) {
                int status = f.get(30, TimeUnit.SECONDS);
                if (status == 201 || status == 200) ok++;
                else { assertTrue(status == 409 || status == 422 || status == 404,
                        "Unexpected concurrent status (lock/transaction failures must not be hidden): " + status); rejected++; }
            }
            assertEquals(operations.size(), sessions.size(), "Each operation must hold its own H2 connection");
            System.out.printf("AGENDA_RACE %s operations=%d confirmed=%d rejected=%d connections=%d newRows=%d historyDelta=%d%n",
                    label, operations.size(), ok, rejected, sessions.size(),
                    appointments.count() - rowsBefore, history.count() - historyBefore);
            return new RaceResult(ok, rejected);
        } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
