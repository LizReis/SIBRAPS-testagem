package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.pet.buscaativa.entities.enums.*;

class AgendaRescheduleTests extends AgendaIntegritySupport {
    @Test void p0501SamePatientCanMoveToAnotherProfessional() throws Exception {
        var original = create("08:00");
        assertEquals(201, post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, original.id())));
        assertEquals(SituacaoAtendimento.REMARCADO_ORIGEM, appointment(original.id()).getSituacaoAtendimento());
        assertEquals(1, active(patient, "08:30"));
    }

    @Test void p0502To05DifferentPatientLeavesAllPersistedStateUntouched() throws Exception {
        var original = create("08:00"); var before = snapshot(patient, original.id());
        var otherBefore = snapshot(other, original.id());
        assertEquals(422, post(dto(other, colleague, "08:30", TurnoEnum.MANHA, original.id())));
        assertEquals(before, snapshot(patient, original.id()));
        assertEquals(otherBefore, snapshot(other, original.id()));
    }

    @Test void p0506MissingOriginal() throws Exception {
        var before = snapshot(patient, null);
        assertEquals(404, post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, Long.MAX_VALUE)));
        assertEquals(before, snapshot(patient, null));
    }

    @Test void p0507ForeignOriginal() throws Exception {
        var original = tx(() -> seed(patient(CapsEnum.CAPS_I), user(TipoUsuario.PROFISSIONAL, UnidadeAtuacao.CAPS_I),
                SituacaoAtendimento.AGENDADO, date, "08:00"));
        var before = snapshot(patient, original.getId());
        assertEquals(404, post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, original.getId())));
        assertEquals(before, snapshot(patient, original.getId()));
    }

    @ParameterizedTest @EnumSource(value = SituacaoAtendimento.class, names = {"PRESENTE", "CANCELADO", "REMARCADO_ORIGEM"})
    void p0508DisallowedOriginalState(SituacaoAtendimento status) throws Exception {
        var original = tx(() -> seed(patient, professional, status, date, "08:00"));
        var before = snapshot(patient, original.getId());
        assertEquals(409, post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, original.getId())));
        assertEquals(before, snapshot(patient, original.getId()));
    }

    @Test void p0509RescheduleAfterAbsencePreservesFlag() throws Exception {
        var original = tx(() -> seed(patient, professional, SituacaoAtendimento.FALTOU, LocalDate.now().minusDays(1), "08:00"));
        assertEquals(201, post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, original.getId())));
        assertTrue(tx(() -> appointments.findAll().stream().anyMatch(a -> a.getAgendamentoOriginal() != null
                && a.getAgendamentoOriginal().getId().equals(original.getId()) && a.isRemarcacaoAposFalta())));
    }

    @Test void p0510RepeatedOriginalCannotCreateSecondReplacement() throws Exception {
        var original = create("08:00");
        assertEquals(201, post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, original.id())));
        var before = snapshot(patient, original.id());
        assertEquals(409, post(dto(patient, professional, "09:00", TurnoEnum.MANHA, original.id())));
        assertEquals(before, snapshot(patient, original.id()));
    }

    @Test void p0510ConcurrentReplacementHasOneSuccess() throws Exception {
        var original = create("08:00");
        var result = race("P05-original", List.of(
                () -> post(dto(patient, colleague, "08:30", TurnoEnum.MANHA, original.id())),
                () -> post(dto(patient, professional, "09:00", TurnoEnum.MANHA, original.id()))));
        assertEquals(new RaceResult(1, 1), result);
        assertEquals(1, tx(() -> appointments.findAll().stream().filter(a -> a.getAgendamentoOriginal() != null
                && a.getAgendamentoOriginal().getId().equals(original.id())).count()));
    }
}
