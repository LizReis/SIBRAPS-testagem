package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Autowired;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.HistoricoPacienteService;

class CanonicalAppointmentTests extends AgendaIntegritySupport {
    @MockitoSpyBean HistoricoPacienteService histories;
    @Autowired com.pet.buscaativa.services.PacienteService patientService;

    @ParameterizedTest @CsvSource({"AGENDADO,AGENDADA,NAO_REGISTRADA,true", "REMARCADO,REMARCADA,NAO_REGISTRADA,true",
            "PRESENTE,AGENDADA,PRESENTE,true", "FALTOU,AGENDADA,FALTOU,false",
            "CANCELADO,CANCELADA,NAO_REGISTRADA,false", "REMARCADO_ORIGEM,SUBSTITUIDA,NAO_REGISTRADA,false"})
    void newExplicitLegacyCommandsProduceCoherentCanonicalStates(SituacaoAtendimento legacy, SituacaoReserva reservation,
            FrequenciaAssistencial attendance, boolean occupied) {
        var a = tx(() -> seed(patient, professional, legacy, date, "08:00"));
        var fresh = appointment(a.getId());
        assertEquals(reservation, fresh.getSituacaoReserva()); assertEquals(attendance, fresh.getFrequenciaAssistencial());
        assertEquals(occupied, fresh.getOcupaVaga()); assertEquals(legacy, fresh.getSituacaoAtendimento());
        assertEquals(legacy.getCodigo(), jdbc.queryForObject("select situacao_atendimento from tb_agendamento where id=?", Integer.class, a.getId()));
    }

    private Agendamento absence(LocalDate day, String hour) {
        return tx(() -> { var a = seed(patient, professional, SituacaoAtendimento.FALTOU, day, hour);
            histories.registrarAlteracaoDeAtendimento(a, SituacaoAtendimento.AGENDADO); return a; });
    }
    private int change(Long id, SituacaoAtendimento next, Integer version) throws Exception {
        var request = patch("/api/agendamentos/" + id + "/status").param("novoStatus", next.name()).param("justificativa", "Retificação sintética após conferência do registro original.")
                .header("Authorization", "Bearer " + tokens.gerarToken(admin));
        if (version != null) request.param("version", version.toString());
        return businessStatus(mvc.perform(request).andReturn().getResponse());
    }

    @Test void rescheduleRetainsMissedOriginalAndCorrectionDoesNotReopenReservation() throws Exception {
        date = LocalDate.now();
        tx(() -> { capacity(colleague, date, TurnoEnum.MANHA, 10); return null; });
        var original = absence(date.minusDays(2), "08:00");
        Long eventId = history.findByAgendamento(original).getFirst().getId();
        assertEquals(201, post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, original.getId())));
        var source = appointment(original.getId());
        assertEquals(SituacaoReserva.SUBSTITUIDA, source.getSituacaoReserva());
        assertEquals(FrequenciaAssistencial.FALTOU, source.getFrequenciaAssistencial()); assertFalse(source.getOcupaVaga());
        assertEquals(1, patients.findById(patient.getId()).orElseThrow().getCountFaltas());
        long rows = appointments.count(), events = history.count();
        assertEquals(200, change(source.getId(), SituacaoAtendimento.PRESENTE, source.getVersion()));
        source = appointment(source.getId());
        assertEquals(SituacaoReserva.SUBSTITUIDA, source.getSituacaoReserva());
        assertEquals(FrequenciaAssistencial.PRESENTE, source.getFrequenciaAssistencial());
        assertEquals(SituacaoAtendimento.REMARCADO_ORIGEM, source.getSituacaoAtendimento()); assertFalse(source.getOcupaVaga());
        assertEquals(rows, appointments.count()); assertEquals(events, history.count());
        assertEquals(TipoEventoHistoricoPaciente.PRESENCA, history.findById(eventId).orElseThrow().getTipo());
        assertEquals(0, patients.findById(patient.getId()).orElseThrow().getCountFaltas());
        var response = mvc.perform(get("/api/agendamentos/" + source.getId())
                .header("Authorization", "Bearer " + tokens.gerarToken(admin))).andReturn().getResponse();
        var body = json.readTree(response.getContentAsString());
        assertEquals("REMARCADO_ORIGEM", body.get("situacaoAtendimento").asText());
        assertEquals("SUBSTITUIDA", body.get("situacaoReserva").asText());
        assertEquals("PRESENTE", body.get("frequenciaAssistencial").asText());
        var listed = mvc.perform(get("/api/pacientes/" + patient.getIdPublico() + "/agendamentos")
                .header("Authorization", "Bearer " + tokens.gerarToken(admin))).andReturn().getResponse();
        assertEquals(200, listed.getStatus());
        var entries = json.readTree(listed.getContentAsString());
        boolean found = false;
        for (var entry : entries) if (entry.get("id").asLong() == source.getId()) {
            found = true;
            assertEquals("SUBSTITUIDA", entry.get("situacaoReserva").asText());
            assertEquals("PRESENTE", entry.get("frequenciaAssistencial").asText());
        }
        assertTrue(found);
        assertEquals(200, change(source.getId(), SituacaoAtendimento.PRESENTE, source.getVersion()));
        assertEquals(source.getVersion(), appointment(source.getId()).getVersion());
    }

    @Test void canceledClinicalFactIsPreservedButRegularizationEndpointIsStillUnavailable() throws Exception {
        date = LocalDate.now().minusDays(10);
        long before = appointments.countByDataAgendamentoAndSituacaoAtendimento(date, SituacaoAtendimento.PRESENTE);
        var a = tx(() -> { var record = seed(patient, professional, SituacaoAtendimento.PRESENTE, date, "08:00");
            record.setSituacaoAtendimento(SituacaoAtendimento.CANCELADO); return appointments.saveAndFlush(record); });
        assertEquals(FrequenciaAssistencial.PRESENTE, appointment(a.getId()).getFrequenciaAssistencial());
        assertFalse(appointment(a.getId()).getOcupaVaga());
        assertEquals(422, change(a.getId(), SituacaoAtendimento.FALTOU, null));
        assertEquals(before + 1, appointments.countByDataAgendamentoAndSituacaoAtendimento(date, SituacaoAtendimento.PRESENTE));
    }

    @Test void operationalResetCannotEraseClinicalFactOrBypassCorrectionPermission() throws Exception {
        date = LocalDate.now().minusDays(10); var a = absence(date, "08:00");
        var before = snapshot(patient, a.getId());
        assertEquals(409, change(a.getId(), SituacaoAtendimento.AGENDADO, null));
        assertEquals(before, snapshot(patient, a.getId()));
        assertEquals(FrequenciaAssistencial.FALTOU, appointment(a.getId()).getFrequenciaAssistencial());
    }

    @Test void readonlyDtoFieldsDoNotCreateAlternativeClinicalWrite() throws Exception {
        var payload = new java.util.HashMap<String, Object>();
        payload.put("usuarioId", professional.getIdPublico()); payload.put("pacienteId", patient.getIdPublico());
        payload.put("dataAgendamento", date); payload.put("turnoAgendamento", "MANHA"); payload.put("horaAtendimento", "08:00");
        payload.put("frequenciaAssistencial", "PRESENTE"); payload.put("situacaoReserva", "CANCELADA"); payload.put("ocupaVaga", false);
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos").header("Authorization", "Bearer " + tokens.gerarToken(admin))
                .contentType("application/json").content(json.writeValueAsString(payload))).andReturn().getResponse();
        assertEquals(201, businessStatus(response));
        var body = json.readTree(response.getContentAsString());
        assertEquals("AGENDADO", body.get("situacaoAtendimento").asText());
        assertEquals("AGENDADA", body.get("situacaoReserva").asText());
        assertEquals("NAO_REGISTRADA", body.get("frequenciaAssistencial").asText());
        assertTrue(body.get("ocupaVaga").asBoolean());
    }

    @Test void nullCommandAndSelfLinkAreRejectedWithoutInventingFrequency() {
        var a = new Agendamento();
        assertThrows(IllegalArgumentException.class, () -> a.setSituacaoAtendimento(null));
        assertThrows(IllegalArgumentException.class, () -> a.setAgendamentoOriginal(a));
        assertNull(a.getFrequenciaAssistencial());
    }

    @Test void correctionOfSubstitutedFactRollsBackTogetherWithVisitAndHistory() {
        var source = absence(LocalDate.now().minusDays(10), "08:00");
        tx(() -> { appointment(source.getId()).setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM); return null; });
        var before = snapshot(patient, source.getId());
        doThrow(new IllegalStateException("synthetic canonical rollback")).when(histories)
                .registrarCorrecaoDeFrequencia(any(Agendamento.class), any(FrequenciaAssistencial.class));
        authenticate();
        assertThrows(IllegalStateException.class, () -> service.atualizarStatus(source.getId(), SituacaoAtendimento.PRESENTE, null, "Retificação sintética após conferência do registro original."));
        assertEquals(before, snapshot(patient, source.getId()));
        assertEquals(FrequenciaAssistencial.FALTOU, appointment(source.getId()).getFrequenciaAssistencial());
    }

    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void unknownLaterFactsFlagProvisionalIndicatorsWithoutInventingVisitDecision(boolean later, boolean previousAlert) {
        var unknown = absence(LocalDate.now().minusDays(later ? 5 : 20), "08:00");
        tx(() -> { seed(patient, colleague, SituacaoAtendimento.PRESENTE, LocalDate.now().minusDays(10), "09:00");
            var p = patients.findById(patient.getId()).orElseThrow(); p.setGatilhoVisitaAcionado(previousAlert);
            p.setClassificacaoRisco(ClassificacaoRisco.AMARELO);
            patients.saveAndFlush(p); return null; });
        jdbc.update("update tb_agendamento set frequencia_assistencial=null,migracao_pendente=true,motivo_migracao_pendente='SYNTHETIC_UNKNOWN' where id=?", unknown.getId());
        authenticate();
        // Exercise the real shared recalculation, not the transition shortcut.
        tx(() -> { patientService.recalcularAssiduidadePaciente(patients.findById(patient.getId()).orElseThrow()); return null; });
        var p = patients.findById(patient.getId()).orElseThrow();
        assertEquals(later, p.getIndicadoresPendentes());
        assertEquals(later && previousAlert, p.getGatilhoVisitaAcionado());
        assertEquals(later ? ClassificacaoRisco.AMARELO : ClassificacaoRisco.VERDE, p.getClassificacaoRisco());
        assertNull(appointment(unknown.getId()).getFrequenciaAssistencial());
        assertEquals(LocalDate.now().minusDays(10), p.getDataUltimaPresenca());
    }

    @ParameterizedTest @CsvSource({"true", "false"})
    void substitutedFrequencyCorrectionStillChecksRealCollisions(boolean samePatient) throws Exception {
        date = LocalDate.now().minusDays(10);
        var original = absence(date, "08:00");
        tx(() -> { appointment(original.getId()).setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
            seed(samePatient ? patient : other, samePatient ? colleague : professional, SituacaoAtendimento.AGENDADO, date, "08:00");
            return null; });
        var before = snapshot(patient, original.getId());
        assertEquals(409, change(original.getId(), SituacaoAtendimento.PRESENTE, null));
        assertEquals(before, snapshot(patient, original.getId()));
        assertEquals(SituacaoReserva.SUBSTITUIDA, appointment(original.getId()).getSituacaoReserva());
        assertEquals(FrequenciaAssistencial.FALTOU, appointment(original.getId()).getFrequenciaAssistencial());
    }
}
