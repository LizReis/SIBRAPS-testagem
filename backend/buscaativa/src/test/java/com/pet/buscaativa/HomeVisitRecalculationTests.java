package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.time.LocalDate;
import java.util.List;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.*;
import com.pet.buscaativa.repositories.*;

class HomeVisitRecalculationTests extends AgendaIntegritySupport {
    @Autowired PacienteService patientService;
    @MockitoSpyBean HistoricoPacienteService histories;
    @Autowired GrupoTerapeuticoRepository groups;
    @Autowired SessaoGrupoRepository sessions;
    @Autowired SessaoGrupoParticipanteRepository participants;

    private Agendamento fact(int daysAgo, SituacaoAtendimento status) {
        return tx(() -> {
            var a = seed(patient, professional, status, LocalDate.now().minusDays(daysAgo), "08:00");
            histories.registrarAlteracaoDeAtendimento(a, SituacaoAtendimento.AGENDADO);
            return a;
        });
    }
    private Agendamento replacement(Agendamento original, int daysAgo) {
        return tx(() -> {
            var source = appointment(original.getId());
            source.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
            var a = seed(patient, colleague, SituacaoAtendimento.FALTOU, LocalDate.now().minusDays(daysAgo), "08:00");
            a.setAgendamentoOriginal(source); a.setRemarcacaoAposFalta(true); appointments.saveAndFlush(a);
            histories.registrarAlteracaoDeAtendimento(a, SituacaoAtendimento.REMARCADO);
            return a;
        });
    }
    private void recalculate() {
        authenticate();
        tx(() -> { patientService.recalcularAssiduidadePaciente(patients.findById(patient.getId()).orElseThrow()); return null; });
    }
    private boolean visit() { return Boolean.TRUE.equals(patients.findById(patient.getId()).orElseThrow().getGatilhoVisitaAcionado()); }
    private int correct(Agendamento a, SituacaoAtendimento status) throws Exception {
        return businessStatus(mvc.perform(patch("/api/agendamentos/" + a.getId() + "/status")
                .param("novoStatus", status.name()).param("justificativa", "Retificação sintética após conferência do registro original.").header("Authorization", "Bearer " + tokens.gerarToken(admin)))
                .andReturn().getResponse());
    }
    private void alertPreviouslyActive() {
        tx(() -> { var p = patients.findById(patient.getId()).orElseThrow(); p.setGatilhoVisitaAcionado(true); patients.saveAndFlush(p); return null; });
    }

    @Test void correctionBeforeStillValidPairMustKeepAlert() throws Exception {
        var corrected = fact(12, SituacaoAtendimento.FALTOU);
        replacement(fact(8, SituacaoAtendimento.FALTOU), 6); alertPreviouslyActive();
        assertEquals(200, correct(corrected, SituacaoAtendimento.PRESENTE));
        assertTrue(visit(), "A later valid missed replacement still requires the alert");
        assertEquals(LocalDate.now().minusDays(12), patients.findById(patient.getId()).orElseThrow().getDataUltimaPresenca());
    }
    @Test void presenceBetweenOriginalAndReplacementInvalidatesThatPair() throws Exception {
        var original = fact(8, SituacaoAtendimento.FALTOU);
        var middle = fact(7, SituacaoAtendimento.FALTOU);
        replacement(original, 6); alertPreviouslyActive();
        assertEquals(200, correct(middle, SituacaoAtendimento.PRESENTE));
        assertFalse(visit());
        assertEquals(1, patients.findById(patient.getId()).orElseThrow().getCountFaltas());
    }
    @Test void laterPairInSuccessiveReschedulesMustSurviveEarlierCorrection() throws Exception {
        var first = fact(10, SituacaoAtendimento.FALTOU);
        var middle = fact(9, SituacaoAtendimento.FALTOU);
        var second = replacement(first, 8);
        replacement(second, 6); alertPreviouslyActive();
        assertEquals(200, correct(middle, SituacaoAtendimento.PRESENTE));
        assertTrue(visit());
    }
    @Test void correctingLatestMissedReplacementClearsAlertAndIsIdempotent() throws Exception {
        var last = replacement(fact(8, SituacaoAtendimento.FALTOU), 6); alertPreviouslyActive();
        long rows = appointments.count(), events = history.count();
        assertEquals(200, correct(last, SituacaoAtendimento.PRESENTE)); assertFalse(visit());
        var before = snapshot(patient, last.getId());
        assertEquals(200, correct(last, SituacaoAtendimento.PRESENTE));
        assertEquals(before, snapshot(patient, last.getId()));
        assertEquals(rows, appointments.count()); assertEquals(events, history.count());
    }
    @Test void recalculationAloneDerivesAlertRatherThanKeepingOldFlag() {
        replacement(fact(8, SituacaoAtendimento.FALTOU), 6);
        recalculate(); assertTrue(visit());
        fact(4, SituacaoAtendimento.PRESENTE); recalculate(); assertFalse(visit());
    }
    @Test void laterPresencePreventsOlderInverseCorrectionFromReactivatingAlert() throws Exception {
        var last = replacement(fact(8, SituacaoAtendimento.FALTOU), 6);
        assertEquals(200, correct(last, SituacaoAtendimento.PRESENTE));
        fact(3, SituacaoAtendimento.PRESENTE);
        assertEquals(200, correct(last, SituacaoAtendimento.FALTOU));
        assertFalse(visit());
        assertEquals(LocalDate.now().minusDays(3), patients.findById(patient.getId()).orElseThrow().getDataUltimaPresenca());
        assertEquals(0, patients.findById(patient.getId()).orElseThrow().getCountFaltas());
    }
    @Test void correctedEvidenceOverridesStaleRescheduleFlag() throws Exception {
        var original = fact(8, SituacaoAtendimento.FALTOU);
        var successor = replacement(original, 6); alertPreviouslyActive();
        assertEquals(200, correct(original, SituacaoAtendimento.PRESENTE));
        assertEquals(SituacaoAtendimento.REMARCADO_ORIGEM, appointment(original.getId()).getSituacaoAtendimento());
        assertEquals(FrequenciaAssistencial.PRESENTE, appointment(original.getId()).getFrequenciaAssistencial());
        assertTrue(appointment(successor.getId()).isRemarcacaoAposFalta());
        assertFalse(visit());
    }
    @Test void unrelatedAbsencesDoNotCreateANumericVisitThreshold() {
        for (int i = 12; i >= 5; i--) fact(i, SituacaoAtendimento.FALTOU);
        recalculate(); assertFalse(visit());
        assertEquals(ClassificacaoRisco.VERMELHO, patients.findById(patient.getId()).orElseThrow().getClassificacaoRisco());
    }
    @ParameterizedTest @EnumSource(value = StatusPresencaGrupo.class, names = {"PRESENTE", "FALTOU"})
    void groupAttendanceSharesChronologyButDoesNotIntroduceAnIndividualVisitRule(StatusPresencaGrupo status) {
        replacement(fact(8, SituacaoAtendimento.FALTOU), 6);
        tx(() -> {
            var g = new GrupoTerapeutico(); g.setCoordenador(professional); g.setTema("Visita sintética"); groups.saveAndFlush(g);
            var s = new SessaoGrupo(); s.setGrupo(g); s.setDataSessao(LocalDate.now().minusDays(3));
            s.setHorario(LocalTime.of(9, 0)); s.setStatus(StatusSessaoGrupo.REALIZADA); sessions.saveAndFlush(s);
            var p = new SessaoGrupoParticipante(); p.setSessaoGrupo(s); p.setPaciente(patient); p.setStatusPresenca(status);
            participants.saveAndFlush(p); return null;
        });
        recalculate(); assertEquals(status == StatusPresencaGrupo.FALTOU, visit());
        if (status == StatusPresencaGrupo.PRESENTE)
            assertEquals(LocalDate.now().minusDays(3), patients.findById(patient.getId()).orElseThrow().getDataUltimaPresenca());
    }
    @Test void foreignUnitCannotChangeVisitThroughFrequencyCorrection() throws Exception {
        var last = replacement(fact(8, SituacaoAtendimento.FALTOU), 6); alertPreviouslyActive();
        var foreign = tx(() -> user(TipoUsuario.ADMINISTRADOR, UnidadeAtuacao.CAPS_I));
        var before = snapshot(patient, last.getId());
        assertEquals(404, businessStatus(mvc.perform(patch("/api/agendamentos/" + last.getId() + "/status")
                .param("novoStatus", "PRESENTE").param("justificativa", "Retificação sintética após conferência do registro original.").header("Authorization", "Bearer " + tokens.gerarToken(foreign)))
                .andReturn().getResponse()));
        assertEquals(before, snapshot(patient, last.getId()));
    }
    @Test void failedHistoryWriteRollsBackVisitAndFrequencyTogether() {
        var last = replacement(fact(8, SituacaoAtendimento.FALTOU), 6); alertPreviouslyActive();
        var before = snapshot(patient, last.getId());
        doThrow(new IllegalStateException("synthetic visit rollback")).when(histories)
                .registrarAlteracaoDeAtendimento(any(Agendamento.class), any(SituacaoAtendimento.class));
        authenticate();
        assertThrows(IllegalStateException.class, () -> service.atualizarStatus(last.getId(), SituacaoAtendimento.PRESENTE, null, "Retificação sintética após conferência do registro original."));
        assertEquals(before, snapshot(patient, last.getId()));
    }
    @RepeatedTest(2) void concurrentCorrectionsKeepAlertForRemainingPair() throws Exception {
        var a = fact(14, SituacaoAtendimento.FALTOU); var b = fact(12, SituacaoAtendimento.FALTOU);
        replacement(fact(8, SituacaoAtendimento.FALTOU), 6); alertPreviouslyActive();
        assertEquals(new RaceResult(2, 0), race("E2A-corrections", List.of(
                () -> correct(a, SituacaoAtendimento.PRESENTE), () -> correct(b, SituacaoAtendimento.PRESENTE))));
        assertTrue(visit());
        assertEquals(LocalDate.now().minusDays(12), patients.findById(patient.getId()).orElseThrow().getDataUltimaPresenca());
    }
}
