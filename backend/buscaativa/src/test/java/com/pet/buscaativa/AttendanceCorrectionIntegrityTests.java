package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.pet.buscaativa.config.AgendaHorarioProperties;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.HistoricoPacienteService;
import com.pet.buscaativa.services.PacienteService;

/** Real persisted fixtures, HTTP authorization, and independent-transaction races for Etapa 1. */
class AttendanceCorrectionIntegrityTests extends AgendaIntegritySupport {
    @MockitoSpyBean HistoricoPacienteService histories;
    @Autowired PacienteService patientService;
    @Autowired AgendaHorarioProperties grid;
    @Autowired GrupoTerapeuticoRepository groups;
    @Autowired SessaoGrupoRepository sessions;
    @Autowired SessaoGrupoParticipanteRepository participants;

    private Agendamento absence(Paciente p, Usuario u, LocalDate day, String hour) {
        return tx(() -> {
            var a = seed(p, u, SituacaoAtendimento.FALTOU, day, hour);
            histories.registrarAlteracaoDeAtendimento(a, SituacaoAtendimento.AGENDADO);
            patientService.recalcularAssiduidadePaciente(patients.findById(p.getId()).orElseThrow());
            return a;
        });
    }

    private int change(Usuario actor, Long id, SituacaoAtendimento next, Integer version) throws Exception {
        var request = patch("/api/agendamentos/" + id + "/status").param("novoStatus", next.name())
                .header("Authorization", "Bearer " + tokens.gerarToken(actor));
        if (version != null) request.param("version", version.toString());
        var response = mvc.perform(request).andReturn().getResponse();
        int code = businessStatus(response);
        if (code == 409 && version == null) {
            assertEquals("Conflito de regra de negócio.", json.readTree(response.getContentAsString()).get("error").asText(),
                    "A technical/version conflict cannot stand in for a real slot collision");
        }
        return code;
    }

    private int correct(Long id) throws Exception { return change(admin, id, SituacaoAtendimento.PRESENTE, null); }

    record AppointmentFact(Long id, Long patientId, Long professionalId, LocalDate day, LocalTime hour,
            TurnoEnum shift, SituacaoAtendimento status, Integer version, Long originalId, boolean afterAbsence) {}
    record HistoryFact(Long id, TipoEventoHistoricoPaciente type, SituacaoAtendimento status,
            String description, LocalDateTime occurred, Integer absences) {}
    record PersistedState(Snapshot patient, Snapshot other, List<AppointmentFact> appointments, List<HistoryFact> events) {}

    private PersistedState persisted(Long target) {
        return tx(() -> new PersistedState(snapshot(patient, target), snapshot(other, target),
                appointments.findAll().stream().map(a -> new AppointmentFact(a.getId(), a.getPaciente().getId(),
                        a.getUsuario().getId(), a.getDataAgendamento(), a.getHoraAtendimento(), a.getTurnoAgendamento(),
                        a.getSituacaoAtendimento(), a.getVersion(),
                        a.getAgendamentoOriginal() == null ? null : a.getAgendamentoOriginal().getId(), a.isRemarcacaoAposFalta()))
                        .sorted(java.util.Comparator.comparing(AppointmentFact::id)).toList(),
                history.findAll().stream().map(h -> new HistoryFact(h.getId(), h.getTipo(), h.getSituacaoAtendimento(),
                        h.getDescricao(), h.getOcorridoEm(), h.getNumeroFaltaConsecutiva()))
                        .sorted(java.util.Comparator.comparing(HistoryFact::id)).toList()));
    }

    static Stream<Arguments> activeCollisions() {
        return Stream.of(SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO, SituacaoAtendimento.PRESENTE)
                .flatMap(status -> Stream.of(Arguments.of(true, status), Arguments.of(false, status)));
    }

    @ParameterizedTest(name = "E1-05/06/09 patientCollision={0}, otherStatus={1}")
    @MethodSource("activeCollisions")
    void realPatientOrProfessionalCollisionRejectsWithoutAnyPersistedChanges(boolean samePatient, SituacaoAtendimento state)
            throws Exception {
        date = LocalDate.now().minusDays(10);
        var target = absence(patient, professional, date, "08:00");
        tx(() -> { seed(samePatient ? patient : other, samePatient ? colleague : professional, state, date, "08:00"); return null; });
        var before = persisted(target.getId());
        assertEquals(409, correct(target.getId()));
        assertEquals(before, persisted(target.getId()));
        assertEquals(1, active(samePatient ? patient : other, "08:00"));
        var response = mvc.perform(patch("/api/agendamentos/" + target.getId() + "/status")
                .param("novoStatus", "PRESENTE").header("Authorization", "Bearer " + tokens.gerarToken(admin)))
                .andReturn().getResponse();
        assertEquals(409, businessStatus(response));
        assertTrue(response.getContentAsString().contains("Regularize o conflito"));
        assertFalse(response.getContentAsString().contains(patient.getNome()));
        assertFalse(response.getContentAsString().contains(other.getNome()));
        assertEquals(before, persisted(target.getId()));
    }

    static Stream<Arguments> inactiveNeighbors() {
        return Stream.of(SituacaoAtendimento.FALTOU, SituacaoAtendimento.CANCELADO, SituacaoAtendimento.REMARCADO_ORIGEM)
                .flatMap(status -> Stream.of(Arguments.of(true, status), Arguments.of(false, status)));
    }

    @ParameterizedTest @MethodSource("inactiveNeighbors")
    void inactiveRecordsDoNotBecomeSlotOccupants(boolean samePatient, SituacaoAtendimento state) throws Exception {
        date = LocalDate.now().minusDays(10);
        var target = absence(patient, professional, date, "08:00");
        var neighbor = tx(() -> seed(samePatient ? patient : other, samePatient ? colleague : professional, state, date, "08:00"));
        var before = snapshot(patient, target.getId());
        assertEquals(200, correct(target.getId()));
        assertEquals(SituacaoAtendimento.PRESENTE, appointment(target.getId()).getSituacaoAtendimento());
        assertEquals(state, appointment(neighbor.getId()).getSituacaoAtendimento());
        assertEquals(before.appointments(), appointments.count());
        assertEquals(before.history(), history.count());
        assertEquals(1, active(patient, "08:00"));
    }

    @Test void e107SamePatientAtDifferentTimesCanBeCorrected() throws Exception {
        date = LocalDate.now().minusDays(10);
        var first = absence(patient, professional, date, "08:00");
        var second = absence(patient, colleague, date, "08:30");
        long rows = appointments.count(), events = history.count();
        assertEquals(200, correct(first.getId()));
        assertEquals(200, correct(second.getId()));
        var p = patients.findById(patient.getId()).orElseThrow();
        assertEquals(0, p.getCountFaltas()); assertEquals(date, p.getDataUltimaPresenca());
        assertEquals(ClassificacaoRisco.VERDE, p.getClassificacaoRisco());
        assertEquals(1, active(patient, "08:00")); assertEquals(1, active(patient, "08:30"));
        assertEquals(rows, appointments.count()); assertEquals(events, history.count());
    }

    @Test void e113ChangedGridDoesNotInvalidateHistoricalFactButStillRejectsNewReservation() throws Exception {
        date = LocalDate.now().minusDays(10);
        var target = absence(patient, professional, date, "08:00");
        var identity = tx(() -> appointments.findAll().stream().filter(a -> a.getId().equals(target.getId()))
                .map(a -> List.of(a.getPaciente().getId(), a.getUsuario().getId(), a.getDataAgendamento(),
                        a.getHoraAtendimento(), a.getTurnoAgendamento())).findFirst().orElseThrow());
        int previousInterval = grid.getIntervaloMinutos();
        try {
            grid.setIntervaloMinutos(45);
            assertFalse(grid.grade(TurnoEnum.MANHA).contains(LocalTime.of(8, 0)));
            assertEquals(200, correct(target.getId()));
            var p = patients.findById(patient.getId()).orElseThrow();
            assertEquals(date, p.getDataUltimaPresenca()); assertEquals(0, p.getCountFaltas());
            date = LocalDate.now().plusDays(30);
            var before = persisted(target.getId());
            assertEquals(422, post(dto("08:00")));
            assertEquals(before, persisted(target.getId()));
            assertEquals(identity, tx(() -> { var a = appointment(target.getId());
                return List.of(a.getPaciente().getId(), a.getUsuario().getId(), a.getDataAgendamento(), a.getHoraAtendimento(), a.getTurnoAgendamento()); }));
        } finally { grid.setIntervaloMinutos(previousInterval); }
    }

    @Test void staleVersionRejectsBeforeChangingStatusOrIndicators() throws Exception {
        date = LocalDate.now().minusDays(10);
        var target = absence(patient, professional, date, "08:00");
        var before = persisted(target.getId());
        assertEquals(409, change(admin, target.getId(), SituacaoAtendimento.PRESENTE, target.getVersion() + 1));
        assertEquals(before, persisted(target.getId()));
    }

    @Test void receptionCannotCorrectAbsenceEvenWhenOfferIsAbsent() throws Exception {
        date = LocalDate.now().minusDays(10);
        var target = absence(patient, professional, date, "08:00");
        var reception = tx(() -> user(TipoUsuario.RECEPCAO, UnidadeAtuacao.CAPS_AD));
        var before = persisted(target.getId());
        assertEquals(422, change(reception, target.getId(), SituacaoAtendimento.PRESENTE, null));
        assertEquals(before, persisted(target.getId()));
    }

    @Test void foreignUnitCannotCorrectAbsence() throws Exception {
        date = LocalDate.now().minusDays(10);
        var target = absence(patient, professional, date, "08:00");
        var foreign = tx(() -> user(TipoUsuario.ADMINISTRADOR, UnidadeAtuacao.CAPS_I));
        var before = persisted(target.getId());
        assertEquals(404, change(foreign, target.getId(), SituacaoAtendimento.PRESENTE, null));
        assertEquals(before, persisted(target.getId()));
    }

    @Test void sameUnitColleagueRetainsCurrentPermissionUntilStageThree() throws Exception {
        date = LocalDate.now().minusDays(10);
        var target = absence(patient, professional, date, "08:00");
        assertEquals(200, change(colleague, target.getId(), SituacaoAtendimento.PRESENTE, null));
        assertEquals(SituacaoAtendimento.PRESENTE, appointment(target.getId()).getSituacaoAtendimento());
    }

    @Test void futureFrequencyStillRejected() throws Exception {
        var target = absence(patient, professional, date, "08:00");
        var before = persisted(target.getId());
        assertEquals(422, correct(target.getId()));
        assertEquals(before, persisted(target.getId()));
    }

    @ParameterizedTest @EnumSource(value = SituacaoAtendimento.class, names = {"CANCELADO", "REMARCADO_ORIGEM"})
    void cancellationRegularizationIsNotIntroduced(SituacaoAtendimento original) throws Exception {
        date = LocalDate.now().minusDays(10);
        var target = tx(() -> seed(patient, professional, original, date, "08:00"));
        var before = persisted(target.getId());
        assertEquals(422, correct(target.getId()));
        assertEquals(before, persisted(target.getId()));
    }

    @ParameterizedTest @EnumSource(value = SituacaoAtendimento.class, names = {"PRESENTE", "FALTOU"})
    void initialFrequencyRegistrationRetainsOperationalPermissionAndDoesNotReacquireOffer(SituacaoAtendimento next)
            throws Exception {
        date = LocalDate.now();
        var target = tx(() -> seed(patient, professional, SituacaoAtendimento.AGENDADO, date, "08:00"));
        var reception = tx(() -> user(TipoUsuario.RECEPCAO, UnidadeAtuacao.CAPS_AD));
        assertEquals(200, change(reception, target.getId(), next, null));
        assertEquals(next, appointment(target.getId()).getSituacaoAtendimento());
        assertEquals(next == SituacaoAtendimento.FALTOU ? 1 : 0, patients.findById(patient.getId()).orElseThrow().getCountFaltas());
    }

    private void groupFrequency(LocalDate day, StatusPresencaGrupo status) {
        var group = new GrupoTerapeutico(); group.setCoordenador(professional); group.setTema("Etapa 1 sintética");
        groups.saveAndFlush(group);
        var session = new SessaoGrupo(); session.setGrupo(group); session.setDataSessao(day);
        session.setHorario(LocalTime.of(9, 0)); session.setStatus(StatusSessaoGrupo.REALIZADA); sessions.saveAndFlush(session);
        var participant = new SessaoGrupoParticipante(); participant.setSessaoGrupo(session);
        participant.setPaciente(patients.findById(patient.getId()).orElseThrow()); participant.setStatusPresenca(status);
        participants.saveAndFlush(participant);
        histories.registrarFrequenciaGrupo(participant.getPaciente(), session, status);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void chronologicalIndicatorsIncludeEarlierAndLaterGroupAttendance(boolean laterPresence) throws Exception {
        date = LocalDate.now().minusDays(10);
        var target = absence(patient, professional, date, "08:00");
        tx(() -> {
            var p = patients.findById(patient.getId()).orElseThrow(); p.setTipoAcompanhamento(TipoAcompanhamento.AMBOS);
            var previous = seed(p, professional, SituacaoAtendimento.PRESENTE, date.minusDays(20), "08:00");
            histories.registrarAlteracaoDeAtendimento(previous, SituacaoAtendimento.AGENDADO);
            var missed = seed(p, professional, SituacaoAtendimento.FALTOU, date.minusDays(3), "08:00");
            histories.registrarAlteracaoDeAtendimento(missed, SituacaoAtendimento.AGENDADO);
            groupFrequency(laterPresence ? date.plusDays(2) : date.minusDays(2), StatusPresencaGrupo.PRESENTE);
            groupFrequency(laterPresence ? date.plusDays(3) : date.minusDays(1), StatusPresencaGrupo.FALTOU);
            if (laterPresence) {
                var last = seed(p, professional, SituacaoAtendimento.FALTOU, date.plusDays(4), "08:00");
                histories.registrarAlteracaoDeAtendimento(last, SituacaoAtendimento.AGENDADO);
            }
            patientService.recalcularAssiduidadePaciente(p);
            return null;
        });
        long rows = appointments.count(), events = history.count();
        assertEquals(200, correct(target.getId()));
        var p = patients.findById(patient.getId()).orElseThrow();
        assertEquals(laterPresence ? 2 : 0, p.getCountFaltas());
        assertEquals(laterPresence ? date.plusDays(2) : date, p.getDataUltimaPresenca());
        assertEquals(laterPresence ? ClassificacaoRisco.AMARELO : ClassificacaoRisco.VERDE, p.getClassificacaoRisco());
        assertEquals(rows, appointments.count()); assertEquals(events, history.count());
        assertEquals(1, history.findByAgendamento(target).size());
        var after = persisted(target.getId());
        assertEquals(200, correct(target.getId())); assertEquals(after, persisted(target.getId()));
    }

    @Test void lateHistoryFailureRollsBackStatusIndicatorsAndHistoryTogether() {
        date = LocalDate.now().minusDays(10);
        var target = absence(patient, professional, date, "08:00");
        var before = persisted(target.getId());
        doThrow(new IllegalStateException("synthetic history failure")).when(histories)
                .registrarAlteracaoDeAtendimento(any(Agendamento.class), any(SituacaoAtendimento.class));
        authenticate();
        var error = assertThrows(IllegalStateException.class,
                () -> service.atualizarStatus(target.getId(), SituacaoAtendimento.PRESENTE, null));
        assertEquals("synthetic history failure", error.getMessage());
        assertEquals(before, persisted(target.getId()));
    }

    @RepeatedTest(3)
    void e110CorrectionAndCreationOfSamePatientSlotHaveOneWinner() throws Exception {
        date = LocalDate.now();
        tx(() -> { capacity(colleague, date, TurnoEnum.MANHA, 10); return null; });
        var target = absence(patient, professional, date, "08:00");
        long rows = appointments.count(), events = history.count();
        assertEquals(new RaceResult(1, 1), race("E1-10-correct-create", List.of(() -> correct(target.getId()),
                () -> post(dto(patient, colleague, "08:00", TurnoEnum.MANHA, null)))));
        boolean corrected = appointment(target.getId()).getSituacaoAtendimento() == SituacaoAtendimento.PRESENTE;
        assertEquals(1, active(patient, "08:00"));
        assertEquals(rows + (corrected ? 0 : 1), appointments.count());
        assertEquals(events + (corrected ? 0 : 1), history.count());
        assertEquals(corrected ? 0 : 1, patients.findById(patient.getId()).orElseThrow().getCountFaltas());
    }

    @RepeatedTest(3)
    void e111SamePatientConflictingCorrectionsAreSerializedAcrossProfessionals() throws Exception {
        date = LocalDate.now().minusDays(10);
        var first = absence(patient, professional, date, "08:00");
        var second = absence(patient, colleague, date, "08:00");
        long rows = appointments.count(), events = history.count();
        assertEquals(new RaceResult(1, 1), race("E1-11-correct-correct-patient", List.of(
                () -> correct(first.getId()), () -> correct(second.getId()))));
        assertEquals(1, active(patient, "08:00"));
        assertEquals(rows, appointments.count()); assertEquals(events, history.count());
        assertEquals(1, history.findByAgendamento(first).size()); assertEquals(1, history.findByAgendamento(second).size());
        boolean secondPresent = appointment(second.getId()).getSituacaoAtendimento() == SituacaoAtendimento.PRESENTE;
        assertEquals(secondPresent ? 0 : 1, patients.findById(patient.getId()).orElseThrow().getCountFaltas());
    }

    @RepeatedTest(3)
    void sameProfessionalConflictingCorrectionsAreSerializedAcrossPatients() throws Exception {
        date = LocalDate.now().minusDays(10);
        var first = absence(patient, professional, date, "08:00");
        var second = absence(other, professional, date, "08:00");
        long rows = appointments.count(), events = history.count();
        assertEquals(new RaceResult(1, 1), race("E1-correct-correct-professional", List.of(
                () -> correct(first.getId()), () -> correct(second.getId()))));
        assertEquals(1, active(patient, "08:00") + active(other, "08:00"));
        assertEquals(rows, appointments.count()); assertEquals(events, history.count());
        assertEquals(1, patients.findById(patient.getId()).orElseThrow().getCountFaltas()
                + patients.findById(other.getId()).orElseThrow().getCountFaltas());
    }

    @RepeatedTest(3)
    void e112CorrectionAndRescheduleCannotBothReplaceTheSameAbsence() throws Exception {
        var target = absence(patient, professional, LocalDate.now().minusDays(10), "08:00");
        long rows = appointments.count();
        assertEquals(new RaceResult(1, 1), race("E1-12-correct-reschedule", List.of(
                () -> correct(target.getId()), () -> post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, target.getId())))));
        var state = appointment(target.getId()).getSituacaoAtendimento();
        boolean corrected = state == SituacaoAtendimento.PRESENTE;
        assertTrue(corrected || state == SituacaoAtendimento.REMARCADO_ORIGEM);
        assertEquals(rows + (corrected ? 0 : 1), appointments.count());
        assertEquals(corrected ? 0 : 1, active(patient, "08:30"));
        assertEquals(corrected ? 0L : 1L, tx(() -> appointments.findAll().stream()
                .filter(a -> a.getAgendamentoOriginal() != null && a.getAgendamentoOriginal().getId().equals(target.getId())).count()));
    }

    @RepeatedTest(3)
    void correctionAndOtherRecordReactivationCannotAcquireTheSameSlot() throws Exception {
        date = LocalDate.now();
        tx(() -> { capacity(colleague, date, TurnoEnum.MANHA, 10); return null; });
        var target = absence(patient, professional, date, "08:00");
        var canceled = tx(() -> seed(patient, colleague, SituacaoAtendimento.CANCELADO, date, "08:00"));
        long rows = appointments.count(), events = history.count();
        assertEquals(new RaceResult(1, 1), race("E1-correct-reactivate", List.of(() -> correct(target.getId()),
                () -> change(admin, canceled.getId(), SituacaoAtendimento.AGENDADO, null))));
        assertEquals(1, active(patient, "08:00"));
        assertEquals(rows, appointments.count()); assertEquals(events, history.count());
    }

    @RepeatedTest(3)
    void twoCorrectionsOfSameVersionCommitOnlyOnce() throws Exception {
        date = LocalDate.now().minusDays(10);
        var target = absence(patient, professional, date, "08:00");
        long rows = appointments.count(), events = history.count();
        assertEquals(new RaceResult(1, 1), race("E1-correct-version", List.of(
                () -> change(admin, target.getId(), SituacaoAtendimento.PRESENTE, target.getVersion()),
                () -> change(admin, target.getId(), SituacaoAtendimento.PRESENTE, target.getVersion()))));
        assertEquals(1, active(patient, "08:00"));
        assertEquals(target.getVersion() + 1, appointment(target.getId()).getVersion());
        assertEquals(rows, appointments.count()); assertEquals(events, history.count());
    }

    @Test void e108IndependentCorrectionsCanBothCommitWithoutOffer() throws Exception {
        date = LocalDate.now().minusDays(10);
        var first = absence(patient, professional, date, "08:00");
        var second = absence(other, colleague, date, "08:00");
        long rows = appointments.count(), events = history.count();
        assertEquals(new RaceResult(2, 0), race("E1-08-independent", List.of(
                () -> correct(first.getId()), () -> correct(second.getId()))));
        assertEquals(1, active(patient, "08:00")); assertEquals(1, active(other, "08:00"));
        assertEquals(rows, appointments.count()); assertEquals(events, history.count());
        assertEquals(0, patients.findById(patient.getId()).orElseThrow().getCountFaltas());
        assertEquals(0, patients.findById(other.getId()).orElseThrow().getCountFaltas());
    }
}
