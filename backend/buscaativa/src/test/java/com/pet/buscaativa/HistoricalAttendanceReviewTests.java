package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.time.LocalDate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.BloqueioAgenda;
import com.pet.buscaativa.entities.Disponibilidade;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.HistoricoPacienteService;
import com.pet.buscaativa.services.PacienteService;

/** Historical corrections ignore later offer changes but retain clinical effects and integrity. */
class HistoricalAttendanceReviewTests extends AgendaIntegritySupport {
    enum Schedule { AVAILABLE, REMOVED, ZERO_CAPACITY, REDUCED_CAPACITY, BLOCKED }
    @Autowired HistoricoPacienteService histories;
    @Autowired PacienteService patientService;

    static Stream<Arguments> correctionCases() {
        return Stream.of(TipoUsuario.ADMINISTRADOR, TipoUsuario.PROFISSIONAL)
                .flatMap(role -> Stream.of(Schedule.values()).map(schedule -> Arguments.of(role, schedule)));
    }

    private Agendamento historical(SituacaoAtendimento targetStatus) {
        date = LocalDate.now().minusDays(10);
        return tx(() -> {
            var weekly = new Disponibilidade(); weekly.setUsuario(professional);
            weekly.setDiaDaSemana(date.getDayOfWeek()); weekly.setTurno(TurnoEnum.MANHA); weekly.setCapacidade(10);
            availability.saveAndFlush(weekly);
            var previous = seed(patient, professional, SituacaoAtendimento.PRESENTE, date.minusDays(20), "08:00");
            histories.registrarAlteracaoDeAtendimento(previous, SituacaoAtendimento.AGENDADO);
            for (int i = 3; i >= 1; i--) {
                var absence = seed(patient, professional, SituacaoAtendimento.FALTOU, date.minusDays(i), "08:00");
                histories.registrarAlteracaoDeAtendimento(absence, SituacaoAtendimento.AGENDADO);
            }
            var target = seed(patient, professional, targetStatus, date, "08:00");
            histories.registrarAlteracaoDeAtendimento(target, SituacaoAtendimento.AGENDADO);
            patientService.recalcularAssiduidadePaciente(patients.findById(patient.getId()).orElseThrow());
            return target;
        });
    }

    private void changeSchedule(Schedule scenario) {
        tx(() -> {
            var weekly = availability.findByUsuarioAndDiaDaSemanaAndTurno(professional, date.getDayOfWeek(), TurnoEnum.MANHA).orElseThrow();
            switch (scenario) {
                case AVAILABLE -> { }
                case REMOVED -> availability.delete(weekly);
                case ZERO_CAPACITY -> { weekly.setCapacidade(0); availability.saveAndFlush(weekly); }
                case REDUCED_CAPACITY -> {
                    // These records fit the original capacity10; capacity is reduced only afterwards.
                    seed(other, professional, SituacaoAtendimento.AGENDADO, date, "07:00");
                    seed(other, professional, SituacaoAtendimento.AGENDADO, date, "07:30");
                    weekly.setCapacidade(1); availability.saveAndFlush(weekly);
                }
                case BLOCKED -> {
                    var block = new BloqueioAgenda(); block.setUsuario(professional);
                    block.setDataInicio(date); block.setDataFim(date); block.setMotivoBloqueio("Synthetic review");
                    blocks.saveAndFlush(block);
                }
            }
            return null;
        });
    }

    private int status(Usuario actor, Long id, SituacaoAtendimento next) throws Exception {
        return businessStatus(mvc.perform(patch("/api/agendamentos/" + id + "/status")
                .param("novoStatus", next.name()).header("Authorization", "Bearer " + tokens.gerarToken(actor)))
                .andReturn().getResponse());
    }

    private void checkIndicators(Long id, SituacaoAtendimento expected) {
        authenticate();
        tx(() -> {
            var p = patients.findById(patient.getId()).orElseThrow();
            var events = history.findByAgendamento(appointment(id));
            var summary = histories.consultar(patient.getIdPublico());
            boolean present = expected == SituacaoAtendimento.PRESENTE;
            assertAll(
                    () -> assertEquals(expected, appointment(id).getSituacaoAtendimento()),
                    () -> assertEquals(present ? 0 : 4, p.getCountFaltas()),
                    () -> assertEquals(present ? date : date.minusDays(20), p.getDataUltimaPresenca()),
                    () -> assertEquals(present ? ClassificacaoRisco.VERDE : ClassificacaoRisco.VERMELHO, p.getClassificacaoRisco()),
                    () -> assertEquals(1, events.size()),
                    () -> assertEquals(present ? TipoEventoHistoricoPaciente.PRESENCA : TipoEventoHistoricoPaciente.FALTA, events.getFirst().getTipo()),
                    () -> assertEquals(present ? 2 : 1, summary.totalPresencas()),
                    () -> assertEquals(present ? 3 : 4, summary.totalFaltas()),
                    () -> assertEquals(p.getCountFaltas(), summary.quantidadeFaltasAtual()),
                    () -> assertEquals(p.getClassificacaoRisco(), summary.classificacaoAtual()));
            return null;
        });
    }

    @ParameterizedTest(name = "historical absence correction: {0}, {1}")
    @MethodSource("correctionCases")
    void historicalAbsenceCorrectionMustSurviveLaterScheduleChanges(TipoUsuario role, Schedule scenario) throws Exception {
        var target = historical(SituacaoAtendimento.FALTOU);
        changeSchedule(scenario);
        long before = history.count();
        Long eventId = history.findByAgendamento(target).getFirst().getId();
        int result = status(role == TipoUsuario.ADMINISTRADOR ? admin : professional, target.getId(), SituacaoAtendimento.PRESENTE);
        System.out.printf("FREQUENCY_REVIEW FALTOU_PRESENTE role=%s schedule=%s status=%d%n", role, scenario, result);
        assertEquals(200, result, "Compatibility regression: main allowed historical correction after schedule changes");
        assertEquals(before, history.count());
        assertEquals(eventId, history.findByAgendamento(target).getFirst().getId());
        checkIndicators(target.getId(), SituacaoAtendimento.PRESENTE);
    }

    @ParameterizedTest(name = "historical presence correction: {0}, {1}")
    @MethodSource("correctionCases")
    void historicalPresenceCorrectionKeepsPreviousBehavior(TipoUsuario role, Schedule scenario) throws Exception {
        var target = historical(SituacaoAtendimento.PRESENTE);
        changeSchedule(scenario);
        long before = history.count();
        Long eventId = history.findByAgendamento(target).getFirst().getId();
        int result = status(role == TipoUsuario.ADMINISTRADOR ? admin : professional, target.getId(), SituacaoAtendimento.FALTOU);
        System.out.printf("FREQUENCY_REVIEW PRESENTE_FALTOU role=%s schedule=%s status=%d%n", role, scenario, result);
        // Existing behavior records a separate risk-change event when the patient becomes red.
        assertEquals(200, result); assertEquals(before + 1, history.count());
        assertEquals(eventId, history.findByAgendamento(target).getFirst().getId());
        checkIndicators(target.getId(), SituacaoAtendimento.FALTOU);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(Schedule.class)
    void cancelledReactivationCharacterizesIntentionalIntegrityGuard(Schedule scenario) throws Exception {
        var original = historical(SituacaoAtendimento.CANCELADO);
        changeSchedule(scenario); var before = snapshot(patient, original.getId());
        int result = status(admin, original.getId(), SituacaoAtendimento.AGENDADO);
        int expected = scenario == Schedule.AVAILABLE ? 200 : 409;
        System.out.printf("FREQUENCY_REVIEW CANCELADO_AGENDADO schedule=%s status=%d%n", scenario, result);
        assertEquals(expected, result);
        if (result != 200) assertEquals(before, snapshot(patient, original.getId()));
        else assertEquals(SituacaoAtendimento.AGENDADO, appointment(original.getId()).getSituacaoAtendimento());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = Schedule.class, names = {"REMOVED", "ZERO_CAPACITY", "REDUCED_CAPACITY", "BLOCKED"})
    void historicalCorrectionIgnoresChangedOfferAndPersistsAllClinicalEffects(Schedule scenario) throws Exception {
        var target = historical(SituacaoAtendimento.FALTOU);
        changeSchedule(scenario); var before = snapshot(patient, target.getId());
        Long eventId = history.findByAgendamento(target).getFirst().getId();
        assertEquals(200, status(admin, target.getId(), SituacaoAtendimento.PRESENTE));
        var after = snapshot(patient, target.getId());
        assertEquals(before.appointments(), after.appointments());
        assertEquals(before.history(), after.history());
        assertEquals(before.version() + 1, after.version());
        assertEquals(eventId, history.findByAgendamento(target).getFirst().getId());
        checkIndicators(target.getId(), SituacaoAtendimento.PRESENTE);
    }

    @Test void correctedFrequencyRemainsUnavailableToReception() throws Exception {
        var target = historical(SituacaoAtendimento.PRESENTE);
        var reception = tx(() -> user(TipoUsuario.RECEPCAO, UnidadeAtuacao.CAPS_AD));
        var before = snapshot(patient, target.getId());
        assertEquals(422, status(reception, target.getId(), SituacaoAtendimento.FALTOU));
        assertEquals(before, snapshot(patient, target.getId()));
    }

    @Test void historicalFrequencyRemainsIsolatedByUnit() throws Exception {
        var target = historical(SituacaoAtendimento.PRESENTE);
        var foreign = tx(() -> user(TipoUsuario.ADMINISTRADOR, UnidadeAtuacao.CAPS_I));
        var before = snapshot(patient, target.getId());
        assertEquals(404, status(foreign, target.getId(), SituacaoAtendimento.FALTOU));
        assertEquals(before, snapshot(patient, target.getId()));
    }

    private Long rescheduleAfterAbsence() {
        var original = tx(() -> seed(patient, professional, SituacaoAtendimento.FALTOU, LocalDate.now().minusDays(1), "08:00"));
        date = LocalDate.now();
        tx(() -> { capacity(colleague, date, TurnoEnum.MANHA, 10); return null; });
        var replacement = service.save(dto(patient, colleague, "08:30", TurnoEnum.MANHA, original.getId()));
        assertTrue(appointment(replacement.id()).isRemarcacaoAposFalta());
        assertEquals(SituacaoAtendimento.REMARCADO_ORIGEM, appointment(original.getId()).getSituacaoAtendimento());
        return replacement.id();
    }

    @Test void rescheduleVisitTriggerAndRepeatedCorrectionsRemainIdempotentWhenScheduleExists() throws Exception {
        Long id = rescheduleAfterAbsence();
        assertEquals(200, status(admin, id, SituacaoAtendimento.FALTOU));
        assertTrue(patients.findById(patient.getId()).orElseThrow().getGatilhoVisitaAcionado());
        var before = snapshot(patient, id);
        assertEquals(200, status(admin, id, SituacaoAtendimento.FALTOU));
        assertEquals(before, snapshot(patient, id));
        assertEquals(200, status(admin, id, SituacaoAtendimento.PRESENTE));
        var p = patients.findById(patient.getId()).orElseThrow();
        assertFalse(p.getGatilhoVisitaAcionado()); assertEquals(0, p.getCountFaltas());
        assertEquals(date, p.getDataUltimaPresenca());
        assertEquals(200, status(admin, id, SituacaoAtendimento.FALTOU));
        p = patients.findById(patient.getId()).orElseThrow();
        assertTrue(p.getGatilhoVisitaAcionado()); assertEquals(1, p.getCountFaltas());
        assertEquals(before.history(), history.count());
    }

    @Test void correctingReplacementAttendanceMustClearVisitEvenAfterCapacityWasClosed() throws Exception {
        Long id = rescheduleAfterAbsence();
        assertEquals(200, status(admin, id, SituacaoAtendimento.FALTOU));
        var before = snapshot(patient, id);
        Long eventId = history.findByAgendamento(appointment(id)).stream()
                .filter(h -> h.getTipo() == TipoEventoHistoricoPaciente.FALTA).findFirst().orElseThrow().getId();
        tx(() -> { var e = exceptions.findByUsuarioAndDataAndTurno(colleague, date, TurnoEnum.MANHA).orElseThrow();
            e.setCapacidade(0); exceptions.saveAndFlush(e); return null; });
        int result = status(admin, id, SituacaoAtendimento.PRESENTE);
        var p = patients.findById(patient.getId()).orElseThrow();
        System.out.printf("FREQUENCY_REVIEW replacement-correction status=%d visit=%s absences=%d%n",
                result, p.getGatilhoVisitaAcionado(), p.getCountFaltas());
        assertEquals(200, result, "Historical correction must be decided separately from consuming a slot");
        assertFalse(p.getGatilhoVisitaAcionado()); assertEquals(0, p.getCountFaltas());
        assertEquals(date, p.getDataUltimaPresenca());
        assertEquals(ClassificacaoRisco.VERDE, p.getClassificacaoRisco());
        assertEquals(before.appointments(), appointments.count());
        assertEquals(before.history(), history.count());
        var event = history.findById(eventId).orElseThrow();
        assertEquals(TipoEventoHistoricoPaciente.PRESENCA, event.getTipo());
        assertEquals(SituacaoAtendimento.PRESENTE, event.getSituacaoAtendimento());
        var after = snapshot(patient, id);
        assertEquals(200, status(admin, id, SituacaoAtendimento.PRESENTE));
        assertEquals(after, snapshot(patient, id));
    }
}
