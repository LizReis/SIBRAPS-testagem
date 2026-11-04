package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.pet.buscaativa.entities.enums.*;

class AgendaCombinedTests extends AgendaIntegritySupport {
    @Test void c01InvalidGridConcurrentRequestsPersistNothing() throws Exception {
        var before = snapshot(patient, null);
        assertEquals(new RaceResult(0, 2), race("C01-grid-concurrent", List.of(
                () -> post(dto("03:17")), () -> post(dto(patient, colleague, "03:17", TurnoEnum.MANHA, null)))));
        assertEquals(before, snapshot(patient, null)); assertEquals(0, active(patient, "03:17"));
        System.out.println("AGENDA_STATE C01 active=0 newRows=0 newHistory=0");
    }

    @Test void c02ValidAndCrossPatientReplacementsCompete() throws Exception {
        var original = create("08:00"); long before = appointments.count(), events = history.count();
        assertEquals(new RaceResult(1, 1), race("C02-remarcacao-concurrent", List.of(
                () -> post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, original.id())),
                () -> post(dto(other, professional, "09:00", TurnoEnum.MANHA, original.id())))));
        assertEquals(SituacaoAtendimento.REMARCADO_ORIGEM, appointment(original.id()).getSituacaoAtendimento());
        assertEquals(1, active(patient, "08:30")); assertEquals(0, active(other, "09:00"));
        assertEquals(before + 1, appointments.count()); assertEquals(events + 2, history.count());
        System.out.println("AGENDA_STATE C02 activeReplacement=1 wrongPatient=0 newRows=1 newHistory=2");
    }

    @Test void c03SamePatientWrongShiftDoesNotAlterOriginal() throws Exception {
        var original = create("08:00"); var before = snapshot(patient, original.id());
        assertEquals(422, post(dto(patient, colleague, "14:00", TurnoEnum.MANHA, original.id())));
        assertEquals(before, snapshot(patient, original.id()));
    }

    @Test void c04CompleteFlowPreservesAllThreeInvariants() throws Exception {
        var original = create("08:00"); long before = appointments.count();
        assertEquals(new RaceResult(1, 3), race("C04-integrated", List.of(
                () -> post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, original.id())),
                () -> post(dto("08:30")),
                () -> post(dto(other, professional, "09:00", TurnoEnum.MANHA, original.id())),
                () -> post(dto(patient, colleague, "08:17", TurnoEnum.MANHA, original.id())))));
        assertEquals(before + 1, appointments.count());
        assertEquals(1, active(patient, "08:30")); assertEquals(0, active(other, "09:00"));
        assertEquals(0, active(patient, "08:17"));
        assertFalse(tx(() -> appointments.findAll().stream().anyMatch(a -> a.getAgendamentoOriginal() != null
                && a.getAgendamentoOriginal().getId().equals(original.id())
                && !a.getPaciente().getId().equals(patient.getId()))));
        System.out.println("AGENDA_STATE C04 activeAtTarget=1 wrongPatient=0 offGrid=0 newRows=1");
    }
}
