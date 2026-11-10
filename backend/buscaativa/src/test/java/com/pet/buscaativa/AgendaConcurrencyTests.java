package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.enums.*;

class AgendaConcurrencyTests extends AgendaIntegritySupport {
    @Test void transactionContentionHasSafeConflictResponse() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRequestURI("/api/agendamentos");
        var response = new com.pet.buscaativa.services.exceptions.ResourceExceptionHandler().transactionConflict(
                new org.springframework.dao.CannotAcquireLockException("internal SQL diagnostic"), request);
        assertEquals(409, response.getStatusCode().value());
        assertFalse(response.getBody().getMessage().contains("internal SQL"));
    }

    @RepeatedTest(5)
    void p0401And06DifferentProfessionalsSamePatientAreSerializedWithoutPartialHistory() throws Exception {
        long historyBefore = history.count(), rowsBefore = appointments.count();
        var r = race("P04-same-patient", List.of(() -> post(dto("08:00")),
                () -> post(dto(patient, colleague, "08:00", TurnoEnum.MANHA, null))));
        assertEquals(new RaceResult(1, 1), r);
        assertEquals(1, active(patient, "08:00"));
        assertEquals(rowsBefore + 1, appointments.count());
        assertEquals(historyBefore + 1, history.count());
        System.out.println("AGENDA_STATE P04-same-patient active=1 newRows=1 newHistory=1");
    }

    @Test void p0402SamePatientDifferentTimes() throws Exception {
        var r = race("P04-different-times", List.of(() -> post(dto("08:00")),
                () -> post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, null))));
        assertEquals(new RaceResult(2, 0), r);
        assertEquals(1, active(patient, "08:00")); assertEquals(1, active(patient, "08:30"));
    }

    @Test void p0403DifferentPatientsDifferentProfessionals() throws Exception {
        var r = race("P04-independent", List.of(() -> post(dto("08:00")),
                () -> post(dto(other, colleague, "08:00", TurnoEnum.MANHA, null))));
        assertEquals(new RaceResult(2, 0), r);
        assertEquals(1, active(patient, "08:00")); assertEquals(1, active(other, "08:00"));
    }

    @RepeatedTest(3)
    void p0404SameProfessionalDifferentPatients() throws Exception {
        long before = appointments.count();
        var r = race("P04-same-professional", List.of(() -> post(dto("08:00")),
                () -> post(dto(other, professional, "08:00", TurnoEnum.MANHA, null))));
        assertEquals(new RaceResult(1, 1), r);
        assertEquals(1, active(patient, "08:00") + active(other, "08:00"));
        assertEquals(before + 1, appointments.count());
    }

    @RepeatedTest(3)
    void p0405And09EightSimultaneousRequests() throws Exception {
        long before = appointments.count(), events = history.count();
        List<Callable<Integer>> calls = IntStream.range(0, 8).mapToObj(i -> (Callable<Integer>) () ->
                post(dto(patient, i % 2 == 0 ? professional : colleague, "08:00", TurnoEnum.MANHA, null))).toList();
        assertEquals(new RaceResult(1, 7), race("P04-eight-requests", calls));
        assertEquals(1, active(patient, "08:00"));
        assertEquals(before + 1, appointments.count()); assertEquals(events + 1, history.count());
        System.out.println("AGENDA_STATE P04-eight-requests active=1 newRows=1 newHistory=1");
    }

    @Test void p0407CreateCompetesWithReschedule() throws Exception {
        var original = create("08:00");
        long before = appointments.count();
        var r = race("P04-create-reschedule", List.of(
                () -> post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, original.id())),
                () -> post(dto("08:30"))));
        assertEquals(new RaceResult(1, 1), r);
        assertEquals(1, active(patient, "08:30"));
        assertEquals(before + 1, appointments.count());
        var status = appointment(original.id()).getSituacaoAtendimento();
        assertTrue(status == SituacaoAtendimento.AGENDADO || status == SituacaoAtendimento.REMARCADO_ORIGEM);
    }

    private void today() {
        date = LocalDate.now();
        tx(() -> { capacity(professional, date, TurnoEnum.MANHA, 10);
            capacity(colleague, date, TurnoEnum.MANHA, 10); return null; });
    }

    private int changeStatus(Long id, SituacaoAtendimento status) throws Exception {
        return businessStatus(mvc.perform(patch("/api/agendamentos/" + id + "/status")
                .param("novoStatus", status.name()).param("justificativa", "Retificação sintética após conferência do registro original.").header("Authorization", "Bearer " + tokens.gerarToken(admin)))
                .andReturn().getResponse());
    }

    @Test void p0408CancellationCompetesWithCreation() throws Exception {
        today(); var original = create("08:00");
        var r = race("P04-cancel-create", List.of(
                () -> changeStatus(original.id(), SituacaoAtendimento.CANCELADO),
                () -> post(dto(patient, colleague, "08:00", TurnoEnum.MANHA, null))));
        assertEquals(SituacaoAtendimento.CANCELADO, appointment(original.id()).getSituacaoAtendimento());
        assertEquals(r.confirmed() - 1, active(patient, "08:00"));
        assertEquals(2, r.confirmed() + r.rejected());
    }

    @Test void p0408ReactivationCompetesWithCreation() throws Exception {
        today(); var original = tx(() -> seed(patient, professional, SituacaoAtendimento.CANCELADO, date, "08:00"));
        var r = race("P04-reactivate-create", List.of(
                () -> changeStatus(original.getId(), SituacaoAtendimento.AGENDADO),
                () -> post(dto(patient, colleague, "08:00", TurnoEnum.MANHA, null))));
        assertEquals(new RaceResult(1, 1), r);
        assertEquals(1, active(patient, "08:00"));
    }

    @Test void p0410ForeignUnitCannotParticipate() throws Exception {
        var foreign = tx(() -> patient(CapsEnum.CAPS_I));
        var before = snapshot(foreign, null);
        var r = race("P04-unit-scope", List.of(() -> post(dto("08:00")),
                () -> post(dto(foreign, colleague, "08:00", TurnoEnum.MANHA, null))));
        assertEquals(new RaceResult(1, 1), r); assertEquals(0, active(foreign, "08:00"));
        var after = snapshot(foreign, null);
        assertEquals(before.absences(), after.absences()); assertEquals(before.risk(), after.risk());
    }
}
