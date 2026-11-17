package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.*;

/** Complemento da Etapa 5.1. Apenas fixtures sintéticas e H2 de teste. */
class Etapa51ComplementaryTests extends CancellationRegularizationSupport {
    private static final String WEEK = "/api/disponibilidade-config/disponibilidade";
    private static final String EX = "/api/disponibilidade-config/excecoes";
    private static final String BLOCK = "/api/bloqueioAgenda-config";
    private static final String REASON_BLOCK = "Bloqueio sintético para validar integridade da agenda.";
    @Autowired DisponibilidadeService weeklyService;
    @Autowired DisponibilidadeExcecaoService exceptionService;
    @Autowired BloqueioAgendaService blockService;

    private int http(Usuario actor, MockHttpServletRequestBuilder builder) throws Exception {
        return businessStatus(mvc.perform(builder.header("Authorization", "Bearer " + tokens.gerarToken(actor)))
                .andReturn().getResponse());
    }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder builder, Object dto) throws Exception {
        return builder.contentType("application/json").content(json.writeValueAsString(dto));
    }
    private int save(Usuario actor, String url, Object dto) throws Exception {
        return http(actor, body(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url), dto));
    }
    private int remove(Usuario actor, String url, Long id) throws Exception {
        return http(actor, delete(url + "/" + id));
    }
    private int recoverAdmin(Agendamento a) throws Exception {
        return businessStatus(recover(admin, a, recovery(a)));
    }
    private int changeRole(TipoUsuario role) throws Exception {
        var dto = new UsuarioDTO(professional.getIdPublico(), professional.getNome(), professional.getEmail(),
                role, professional.getUnidadeAtuacao(), null, null, null);
        return http(admin, body(put("/api/usuarios/" + professional.getIdPublico()), dto));
    }
    private int closePatient() throws Exception {
        var dto = new EncerramentoPacienteDTO(MotivoEncerramento.OUTRO, StatusPaciente.INATIVO,
                "Encerramento sintético para teste de concorrência.");
        return http(admin, body(patch("/api/pacientes/" + patient.getIdPublico() + "/encerrar"), dto));
    }
    private int normalized(int code) { return code == 204 ? 200 : code; }
    private void assertRecoveryAuditMatches(Agendamento a) {
        var current = appointment(a.getId());
        boolean recovered = current.getSituacaoReserva() == SituacaoReserva.AGENDADA;
        assertEquals(recovered, Boolean.TRUE.equals(current.getOcupaVaga()));
        assertEquals(FrequenciaAssistencial.NAO_REGISTRADA, current.getFrequenciaAssistencial());
        assertEquals(recovered ? 1L : 0L,
                regularizations.countByAgendamento_IdAndTipoRegularizacao(a.getId(), "RECUPERACAO_RESERVA"));
        assertEquals(a.getVersion() + (recovered ? 1 : 0), current.getVersion());
    }
    private LocalDate next() { return DAY.plusDays(7); }
    private Disponibilidade weekly(Usuario owner, LocalDate day, TurnoEnum turn, int cap) {
        var d = new Disponibilidade(); d.setUsuario(owner); d.setDiaDaSemana(day.getDayOfWeek());
        d.setTurno(turn); d.setCapacidade(cap); return availability.saveAndFlush(d);
    }
    private DisponibilidadeExcecao exception(Usuario owner, LocalDate day, TurnoEnum turn, int cap) {
        capacity(owner, day, turn, cap);
        return exceptions.findByUsuarioAndDataAndTurno(owner, day, turn).orElseThrow();
    }
    private BloqueioAgenda block(Usuario owner, LocalDate day) {
        var b = new BloqueioAgenda(); b.setUsuario(owner); b.setDataInicio(day); b.setDataFim(day);
        b.setMotivoBloqueio(REASON_BLOCK); return blocks.saveAndFlush(b);
    }
    private BloqueioAgendaDTO blockDto(Long id, Usuario owner, LocalDate day) {
        return new BloqueioAgendaDTO(id, owner.getIdPublico(), day, day, REASON_BLOCK);
    }
    private Agendamento seedAfternoon(Paciente p, Usuario u, LocalDate day) {
        var a = seed(p, u, SituacaoAtendimento.AGENDADO, day, "15:00");
        a.setTurnoAgendamento(TurnoEnum.TARDE); return appointments.saveAndFlush(a);
    }

    @Test void actualRoleChangeEndpointBlocksRecoveryButNotHistoricalFact() throws Exception {
        var future = canceled(true); var past = canceled(false);
        assertEquals(200, changeRole(TipoUsuario.RECEPCAO));
        var before = database();
        assertEquals(422, recoverAdmin(future)); assertEquals(before, database());
        assertEquals(200, factual(admin, past, fact(past, FrequenciaAssistencial.PRESENTE)).getStatus());
        assertCanceledFact(past, FrequenciaAssistencial.PRESENTE);
        var formerOwner = users.findById(professional.getId()).orElseThrow();
        assertEquals(403, correct(formerOwner, appointment(past.getId()), FrequenciaAssistencial.FALTOU));
        assertCanceledFact(past, FrequenciaAssistencial.PRESENTE);
        assertEquals(200, correct(admin, appointment(past.getId()), FrequenciaAssistencial.FALTOU));
        assertCanceledFact(past, FrequenciaAssistencial.FALTOU);
    }
    @Test void actualClosureEndpointBlocksRecoveryAndKeepsHistoricalFactPossible() throws Exception {
        var future = canceled(true); var past = canceled(false);
        assertEquals(204, closePatient());
        var before = database();
        assertEquals(422, recoverAdmin(future)); assertEquals(before, database());
        assertEquals(200, factual(admin, past, fact(past, FrequenciaAssistencial.FALTOU)).getStatus());
        assertCanceledFact(past, FrequenciaAssistencial.FALTOU);
        var p = patients.findById(patient.getId()).orElseThrow();
        assertEquals(StatusPaciente.INATIVO, p.getStatusPaciente());
        assertEquals(MotivoEncerramento.OUTRO, p.getMotivoEncerramento());
    }
    @ParameterizedTest @ValueSource(strings={"inactivate", "close", "role"})
    void competingEligibilityChangeAndRecoveryHaveOnlyAllowedSerialOutcomes(String operation) throws Exception {
        var a = canceled(true); var recovered = new AtomicInteger(-1); var changed = new AtomicInteger(-1);
        var r = race("E51-eligibility-" + operation, List.of(
                () -> { int code = recoverAdmin(a); recovered.set(code); return code; },
                () -> { int code = switch(operation) {
                    case "inactivate" -> http(admin, delete("/api/pacientes/" + patient.getIdPublico()));
                    case "close" -> closePatient();
                    default -> changeRole(TipoUsuario.RECEPCAO);
                }; changed.set(code); return normalized(code); }));
        assertEquals(operation.equals("role") ? 200 : 204, changed.get());
        assertTrue(recovered.get() == 200 || recovered.get() == 422, "Unexpected recovery result: " + recovered.get());
        assertEquals(recovered.get() == 200 ? new RaceResult(2,0) : new RaceResult(1,1), r);
        if (operation.equals("role")) assertEquals(TipoUsuario.RECEPCAO, users.findById(professional.getId()).orElseThrow().getTipoUsuario());
        else assertEquals(StatusPaciente.INATIVO, patients.findById(patient.getId()).orElseThrow().getStatusPaciente());
        // A recuperação pode preceder legitimamente o encerramento; não se exige cancelamento automático.
        assertEquals(recovered.get() == 200, appointment(a.getId()).getOcupaVaga());
        assertRecoveryAuditMatches(a);
    }

    @Test void weeklyRemovalWithDateExceptionStillCoveringReservationIsAllowed() throws Exception {
        var day = next(); var w = tx(() -> { var d = weekly(professional, day, TurnoEnum.MANHA, 3);
            exception(professional, day, TurnoEnum.MANHA, 1);
            seed(patient, professional, SituacaoAtendimento.AGENDADO, day, "08:00"); return d; });
        assertEquals(204, remove(admin, WEEK, w.getId()));
        assertFalse(availability.findById(w.getId()).isPresent());
        assertEquals(1, exceptions.findByUsuarioAndDataAndTurno(professional, day, TurnoEnum.MANHA).orElseThrow().getCapacidade());
    }
    @Test void weeklyRemovalProtectsFarFutureReservation() throws Exception {
        var day = DAY.plusYears(3); var w = tx(() -> { var d = weekly(professional, day, TurnoEnum.MANHA, 3);
            seed(patient, professional, SituacaoAtendimento.AGENDADO, day, "08:00"); return d; });
        var before = database();
        assertEquals(409, remove(admin, WEEK, w.getId())); assertEquals(before, database());
    }
    @ParameterizedTest @ValueSource(strings={"day", "shift", "owner"})
    void weeklyMoveWithNoAffectedReservationsIsAllowed(String field) throws Exception {
        var day = next(); var w = tx(() -> weekly(professional, day, TurnoEnum.MANHA, 3));
        var dest = field.equals("owner") ? colleague : professional;
        var destDay = field.equals("day") ? day.plusDays(1) : day;
        var destShift = field.equals("shift") ? TurnoEnum.TARDE : TurnoEnum.MANHA;
        assertEquals(200, save(admin, WEEK, new DisponibilidadeDTO(w.getId(), dest.getIdPublico(), destDay.getDayOfWeek(), destShift, 3)));
        tx(() -> { var moved = availability.findById(w.getId()).orElseThrow();
            assertEquals(dest.getId(), moved.getUsuario().getId()); assertEquals(destDay.getDayOfWeek(), moved.getDiaDaSemana());
            assertEquals(destShift, moved.getTurno()); return null; });
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void exceptionRemovalChecksActualWeeklyFallback(boolean sufficient) throws Exception {
        var day = next(); var e = tx(() -> { weekly(professional, day, TurnoEnum.MANHA, sufficient ? 2 : 1);
            var ex = exception(professional, day, TurnoEnum.MANHA, 3);
            seed(patient, professional, SituacaoAtendimento.AGENDADO, day, "08:00");
            seed(other, professional, SituacaoAtendimento.AGENDADO, day, "09:00"); return ex; });
        var before = database();
        assertEquals(sufficient ? 204 : 409, remove(admin, EX, e.getId()));
        if (!sufficient) assertEquals(before, database());
        else assertFalse(exceptions.findById(e.getId()).isPresent());
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void exceptionTransferChecksDestinationAsWellAsOrigin(boolean sufficient) throws Exception {
        var day = next(); var dest = day.plusDays(1);
        var e = tx(() -> { weekly(professional, day, TurnoEnum.MANHA, 2);
            weekly(professional, dest, TurnoEnum.MANHA, 2);
            var ex = exception(professional, day, TurnoEnum.MANHA, 3);
            seed(patient, professional, SituacaoAtendimento.AGENDADO, day, "08:00");
            seed(patient, professional, SituacaoAtendimento.AGENDADO, dest, "08:00");
            seed(other, professional, SituacaoAtendimento.AGENDADO, dest, "09:00"); return ex; });
        var before = database();
        assertEquals(sufficient ? 200 : 409, save(admin, EX,
                new DisponibilidadeExcecaoDTO(e.getId(), professional.getIdPublico(), dest, TurnoEnum.MANHA, sufficient ? 2 : 1)));
        if (!sufficient) assertEquals(before, database());
        else assertEquals(dest, exceptions.findById(e.getId()).orElseThrow().getData());
    }
    @Test void zeroCapacityExceptionDoesNotAddToWeeklyCapacity() throws Exception {
        var day = next(); tx(() -> { weekly(professional, day, TurnoEnum.TARDE, 5); return null; });
        assertEquals(200, save(admin, EX, new DisponibilidadeExcecaoDTO(null, professional.getIdPublico(), day, TurnoEnum.TARDE, 0)));
        var dto = new AgendamentoDTO(null, professional.getIdPublico(), null, patient.getIdPublico(), null, null,
                day, TurnoEnum.TARDE, LocalTime.of(15,0), SituacaoAtendimento.AGENDADO, null, null);
        var before = database(); int code = post(dto);
        assertTrue(code == 409 || code == 422, "Unexpected response: " + code);
        assertEquals(before, database());
    }
    @ParameterizedTest @ValueSource(strings={"origin", "destination"})
    void professionalMustBeAuthorizedOnBothSides(String side) throws Exception {
        var origin = side.equals("origin") ? colleague : professional;
        var dest = side.equals("origin") ? professional : colleague;
        var w = tx(() -> weekly(origin, next(), TurnoEnum.MANHA, 3));
        var before = database();
        assertEquals(403, save(professional, WEEK, new DisponibilidadeDTO(w.getId(), dest.getIdPublico(), next().getDayOfWeek(), TurnoEnum.MANHA, 3)));
        assertEquals(before, database());
    }
    @Test void crossUnitOriginAndDestinationDoNotLeakOrChangeConfigurations() throws Exception {
        var foreign = tx(() -> user(TipoUsuario.PROFISSIONAL, UnidadeAtuacao.CAPS_I));
        var w = tx(() -> weekly(foreign, next(), TurnoEnum.MANHA, 3));
        var local = tx(() -> weekly(professional, next(), TurnoEnum.MANHA, 3));
        var before = database();
        assertEquals(404, save(admin, WEEK, new DisponibilidadeDTO(w.getId(), professional.getIdPublico(), next().plusDays(1).getDayOfWeek(), TurnoEnum.MANHA, 3)));
        assertEquals(404, save(admin, WEEK, new DisponibilidadeDTO(local.getId(), foreign.getIdPublico(), next().plusDays(1).getDayOfWeek(), TurnoEnum.MANHA, 3)));
        assertEquals(before, database());
    }
    @Test void blockingTransferCannotCoverExistingReservation() throws Exception {
        var a = canceled(true); assertEquals(200, recoverAdmin(a));
        var b = tx(() -> block(colleague, DAY)); var before = database();
        assertEquals(409, save(admin, BLOCK, blockDto(b.getId(), professional, DAY)));
        assertEquals(before, database()); assertRecoveryAuditMatches(a);
    }
    @Test void legitimateBlockTransferAndDeletionRemainAvailable() throws Exception {
        var b = tx(() -> block(professional, next()));
        assertEquals(200, save(admin, BLOCK, blockDto(b.getId(), colleague, next().plusDays(1))));
        assertFalse(blocks.isDataBloqueadaParaUsuario(professional, next()));
        assertTrue(blocks.isDataBloqueadaParaUsuario(colleague, next().plusDays(1)));
        assertEquals(204, remove(admin, BLOCK, b.getId()));
        assertFalse(blocks.findById(b.getId()).isPresent());
    }
    @Test void recoveryVersusBlockMoveKeepsReservationOrBlockButNotBoth() throws Exception {
        var a = canceled(true); var b = tx(() -> block(colleague, DAY));
        var r = race("E51-recovery-block-move", List.of(() -> recoverAdmin(a),
                () -> save(admin, BLOCK, blockDto(b.getId(), professional, DAY))));
        assertEquals(new RaceResult(1,1), r);
        assertNotEquals(appointment(a.getId()).getOcupaVaga(), blocks.isDataBloqueadaParaUsuario(professional, DAY));
        assertRecoveryAuditMatches(a);
    }
    @Test void recoveryVersusWeeklyOwnerTransferLocksOldAndNewProfessional() throws Exception {
        var a = canceled(true);
        var w = tx(() -> { exceptions.delete(exceptions.findByUsuarioAndDataAndTurno(professional,DAY,TurnoEnum.TARDE).orElseThrow());
            exceptions.flush(); return weekly(professional,DAY,TurnoEnum.TARDE,3); });
        var target = new DisponibilidadeDTO(w.getId(), colleague.getIdPublico(), DAY.getDayOfWeek(), TurnoEnum.TARDE, 3);
        var r = race("E51-recovery-week-owner", List.of(() -> recoverAdmin(a), () -> save(admin, WEEK, target)));
        assertEquals(new RaceResult(1,1), r);
        assertEquals(appointment(a.getId()).getOcupaVaga(), availability.findByUsuarioAndDiaDaSemanaAndTurno(professional,DAY.getDayOfWeek(),TurnoEnum.TARDE).isPresent());
        assertRecoveryAuditMatches(a);
    }
    @Test void oppositeOwnerTransfersWithDistinctDestinationsCanBothCommit() throws Exception {
        var day = next();
        var w1 = tx(() -> weekly(professional, day, TurnoEnum.MANHA, 3));
        var w2 = tx(() -> weekly(colleague, day.plusDays(1), TurnoEnum.MANHA, 4));
        var r = race("E51-opposite-owner-transfer", List.of(
                () -> save(admin, WEEK, new DisponibilidadeDTO(w1.getId(), colleague.getIdPublico(), day.getDayOfWeek(), TurnoEnum.MANHA, 3)),
                () -> save(admin, WEEK, new DisponibilidadeDTO(w2.getId(), professional.getIdPublico(), day.plusDays(1).getDayOfWeek(), TurnoEnum.MANHA, 4))));
        assertEquals(new RaceResult(2,0), r);
        assertEquals(w1.getId(), availability.findByUsuarioAndDiaDaSemanaAndTurno(colleague, day.getDayOfWeek(), TurnoEnum.MANHA).orElseThrow().getId());
        assertEquals(w2.getId(), availability.findByUsuarioAndDiaDaSemanaAndTurno(professional, day.plusDays(1).getDayOfWeek(), TurnoEnum.MANHA).orElseThrow().getId());
    }
    @Test void concurrentWeeklyMoveAndRemovalNeverRecreatesDeletedConfiguration() throws Exception {
        var day = next(); var w = tx(() -> weekly(professional, day, TurnoEnum.MANHA, 3));
        var dto = new DisponibilidadeDTO(w.getId(), colleague.getIdPublico(), day.getDayOfWeek(), TurnoEnum.MANHA, 3);
        var deleted = new AtomicInteger(-1);
        var r = race("E51-move-delete", List.of(() -> save(admin, WEEK, dto), () -> {
            int code = remove(admin, WEEK, w.getId()); deleted.set(code); return normalized(code); }));
        assertTrue(r.confirmed() >= 1); assertEquals(2, r.confirmed() + r.rejected());
        assertFalse(availability.findByUsuarioAndDiaDaSemanaAndTurno(professional, day.getDayOfWeek(), TurnoEnum.MANHA).isPresent());
        if (deleted.get() == 204) assertFalse(availability.findById(w.getId()).isPresent());
        else assertEquals(w.getId(), availability.findByUsuarioAndDiaDaSemanaAndTurno(colleague, day.getDayOfWeek(), TurnoEnum.MANHA).orElseThrow().getId());
    }
    @ParameterizedTest @ValueSource(strings={"weekly", "exception", "block"})
    void enclosingTransactionRollbackRestoresRealFlushedOfferWrites(String type) {
        var day = next();
        var w = tx(() -> weekly(professional, day, TurnoEnum.MANHA, 3));
        var e = tx(() -> exception(professional, day, TurnoEnum.TARDE, 3));
        var b = tx(() -> block(professional, day.plusDays(2)));
        var before = database(); authenticate();
        var failure = assertThrows(IllegalStateException.class, () -> tx(() -> {
            switch(type) {
                case "weekly" -> { weeklyService.save(new DisponibilidadeDTO(w.getId(), colleague.getIdPublico(), day.getDayOfWeek(), TurnoEnum.MANHA, 4), admin.getEmail());
                    assertEquals(4, availability.findById(w.getId()).orElseThrow().getCapacidade()); }
                case "exception" -> { exceptionService.save(new DisponibilidadeExcecaoDTO(e.getId(), colleague.getIdPublico(), day, TurnoEnum.TARDE, 4), admin.getEmail());
                    assertEquals(4, exceptions.findById(e.getId()).orElseThrow().getCapacidade()); }
                default -> { blockService.save(blockDto(b.getId(), colleague, day.plusDays(2)), admin.getEmail());
                    assertTrue(blocks.isDataBloqueadaParaUsuario(colleague, day.plusDays(2))); }
            }
            throw new IllegalStateException("synthetic rollback after real flush");
        }));
        assertEquals("synthetic rollback after real flush", failure.getMessage());
        assertEquals(before, database());
    }
    @Test void institutionalMidnightChangesWeeklyRemovalProtectionAtCorrectDate() throws Exception {
        var day = LocalDate.of(2000,1,2); // Domingo; relógio fixo independente da JVM.
        var w = tx(() -> { var d = weekly(professional, day, TurnoEnum.MANHA, 3);
            seed(patient, professional, SituacaoAtendimento.AGENDADO, day, "08:00"); return d; });
        now(day.atTime(23,59)); var before = database();
        assertEquals(409, remove(admin, WEEK, w.getId())); assertEquals(before, database());
        now(day.plusDays(1).atStartOfDay());
        assertEquals(204, remove(admin, WEEK, w.getId()));
        assertFalse(availability.findById(w.getId()).isPresent());
    }
}
