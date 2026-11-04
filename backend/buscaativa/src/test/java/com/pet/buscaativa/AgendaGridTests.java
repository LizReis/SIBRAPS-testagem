package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.pet.buscaativa.entities.BloqueioAgenda;
import com.pet.buscaativa.entities.dto.AgendamentoDTO;
import com.pet.buscaativa.entities.enums.TurnoEnum;

class AgendaGridTests extends AgendaIntegritySupport {
    @ParameterizedTest
    @CsvSource({"MANHA,07:00", "MANHA,08:00", "MANHA,11:30", "TARDE,13:00", "TARDE,17:30", "TARDE,18:30"})
    void p0301And02And11ValidBoundariesAreInclusive(TurnoEnum shift, String time) throws Exception {
        assertEquals(201, post(dto(patient, professional, time, shift, null)));
        assertEquals(1, active(patient, time));
    }

    @ParameterizedTest
    @ValueSource(strings = {"00:00", "03:17", "06:59", "11:31", "12:00", "18:30", "23:59", "08:17"})
    void p0303To09OriginalEightInvalidMorningTimes(String time) throws Exception {
        var before = snapshot(patient, null);
        assertEquals(422, post(dto(time)));
        assertEquals(before, snapshot(patient, null));
    }

    @ParameterizedTest
    @CsvSource({"TARDE,12:59", "TARDE,18:31", "TARDE,08:00", "TARDE,12:00", "TARDE,13:01",
            "MANHA,08:00:01", "MANHA,08:00:00.000000001"})
    void p0305To10BoundariesAndSubMinuteTimes(TurnoEnum shift, String time) throws Exception {
        var before = snapshot(patient, null);
        assertEquals(422, post(dto(patient, professional, time, shift, null)));
        assertEquals(before, snapshot(patient, null));
    }

    @Test void p0312UnconfiguredDayIsUnavailable() throws Exception {
        var d = dto("08:00");
        var noAvailability = new AgendamentoDTO(null, d.usuarioId(), null, d.pacienteId(), null, null,
                date.plusDays(1), d.turnoAgendamento(), d.horaAtendimento(), null, null, null);
        var before = snapshot(patient, null);
        assertEquals(409, post(noAvailability)); assertEquals(before, snapshot(patient, null));
    }

    @Test void p0313ExhaustedCapacity() throws Exception {
        tx(() -> { var e = exceptions.findByUsuarioAndDataAndTurno(professional, date, TurnoEnum.MANHA).orElseThrow();
            e.setCapacidade(1); exceptions.saveAndFlush(e); return null; });
        create("08:00"); var before = snapshot(patient, null);
        assertEquals(409, post(dto(other, professional, "08:30", TurnoEnum.MANHA, null)));
        assertEquals(before, snapshot(patient, null));
    }

    @Test void p0314BlockedAgenda() throws Exception {
        tx(() -> { var b = new BloqueioAgenda(); b.setUsuario(professional); b.setDataInicio(date);
            b.setDataFim(date); b.setMotivoBloqueio("Teste sintético"); return blocks.saveAndFlush(b); });
        var before = snapshot(patient, null);
        assertEquals(409, post(dto("08:00"))); assertEquals(before, snapshot(patient, null));
        authenticate();
        assertEquals("AGENDA_BLOQUEADA", service.buscarHorariosDisponiveis(professional.getIdPublico(), date, TurnoEnum.MANHA).motivoIndisponibilidade());
    }

    @Test void p0315And17OccupiedSlotIsNotAvailable() throws Exception {
        create("08:00"); var before = snapshot(patient, null);
        var listed = service.buscarHorariosDisponiveis(professional.getIdPublico(), date, TurnoEnum.MANHA);
        assertTrue(listed.horarios().stream().anyMatch(h -> h.hora().equals(LocalTime.of(8, 0)) && !h.disponivel()));
        assertEquals(409, post(dto(other, professional, "08:00", TurnoEnum.MANHA, null)));
        assertEquals(before, snapshot(patient, null));
    }

    @Test void p0316ListedSlotCanBeBooked() throws Exception {
        var listed = service.buscarHorariosDisponiveis(professional.getIdPublico(), date, TurnoEnum.MANHA);
        var hour = listed.horarios().stream().filter(h -> h.disponivel()).findFirst().orElseThrow().hora();
        assertEquals(201, post(dto(hour.toString())));
        authenticate();
        var updated = service.buscarHorariosDisponiveis(professional.getIdPublico(), date, TurnoEnum.MANHA);
        assertEquals(listed.vagasRestantesTurno() - 1, updated.vagasRestantesTurno());
    }

    @ParameterizedTest @ValueSource(strings = {"turno", "hora", "data", "paciente", "profissional"})
    void p0318MissingFieldsAreValidationErrors(String field) throws Exception {
        var d = dto("08:00");
        var missing = new AgendamentoDTO(null, field.equals("profissional") ? null : d.usuarioId(), null,
                field.equals("paciente") ? null : d.pacienteId(), null, null, field.equals("data") ? null : date,
                field.equals("turno") ? null : d.turnoAgendamento(), field.equals("hora") ? null : d.horaAtendimento(), null, null, null);
        var before = snapshot(patient, null); assertEquals(422, post(missing));
        assertEquals(before, snapshot(patient, null));
    }

    @ParameterizedTest @ValueSource(strings = {"invalid-shift", "invalid-hour"})
    void p0318MalformedValuesAreBadRequests(String scenario) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("usuarioId", professional.getIdPublico()); body.put("pacienteId", patient.getIdPublico());
        body.put("dataAgendamento", date.toString());
        body.put("turnoAgendamento", scenario.equals("invalid-shift") ? "INVALIDO" : "MANHA");
        body.put("horaAtendimento", scenario.equals("invalid-hour") ? "25:99" : "08:00");
        var before = snapshot(patient, null);
        int status = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos")
                .header("Authorization", "Bearer " + tokens.gerarToken(admin)).contentType("application/json")
                .content(json.writeValueAsString(body))).andReturn().getResponse().getStatus();
        assertEquals(400, status); assertEquals(before, snapshot(patient, null));
    }
}
