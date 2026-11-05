package com.pet.buscaativa;

import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.enums.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.pet.buscaativa.services.HistoricoPacienteService;

/** One-off fixture utility, deliberately not a *Tests class. Run only against the pre-canonical model.
 * The migration regression suite consumes its immutable SQL export, not this generator.
 */
class LegacyAgendaFixtureGenerator extends AgendaIntegritySupport {
    @Autowired HistoricoPacienteService histories;
    @Test void exportOldSchema() {
        var columns = jdbc.queryForList("select column_name from information_schema.columns where table_name='TB_AGENDAMENTO'", String.class);
        if (columns.contains("SITUACAO_RESERVA")) throw new IllegalStateException("Generate only from pre-canonical source 4f933af");
        date = LocalDate.of(2026, 1, 1);
        tx(() -> {
            seed(patient, professional, SituacaoAtendimento.AGENDADO, date, "07:00");
            var old = seed(patient, professional, SituacaoAtendimento.REMARCADO_ORIGEM, date.minusDays(2), "07:30");
            var moved = seed(patient, colleague, SituacaoAtendimento.REMARCADO, date, "07:30");
            moved.setAgendamentoOriginal(old); appointments.saveAndFlush(moved);
            for (var state : new SituacaoAtendimento[]{SituacaoAtendimento.PRESENTE, SituacaoAtendimento.FALTOU, SituacaoAtendimento.CANCELADO}) {
                var a = seed(patient, professional, state, date, state == SituacaoAtendimento.PRESENTE ? "08:00" : state == SituacaoAtendimento.FALTOU ? "08:30" : "09:00");
                if (state != SituacaoAtendimento.CANCELADO) histories.registrarAlteracaoDeAtendimento(a, SituacaoAtendimento.AGENDADO);
            }
            Agendamento previous = null;
            for (int i = 0; i < 3; i++) {
                var a = seed(patient, professional, SituacaoAtendimento.FALTOU, date.plusDays(i), "10:00");
                histories.registrarAlteracaoDeAtendimento(a, SituacaoAtendimento.AGENDADO);
                if (previous != null) {
                    previous.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM); appointments.saveAndFlush(previous);
                    a.setAgendamentoOriginal(previous); a.setRemarcacaoAposFalta(true); appointments.saveAndFlush(a);
                }
                previous = a;
            }
            var canceled = seed(patient, colleague, SituacaoAtendimento.PRESENTE, date.minusDays(3), "11:00");
            histories.registrarAlteracaoDeAtendimento(canceled, SituacaoAtendimento.AGENDADO);
            canceled.setSituacaoAtendimento(SituacaoAtendimento.CANCELADO); appointments.saveAndFlush(canceled);
            var inconsistent = seed(other, colleague, SituacaoAtendimento.FALTOU, date.plusDays(4), "11:00");
            inconsistent.setAgendamentoOriginal(old); inconsistent.setRemarcacaoAposFalta(true); appointments.saveAndFlush(inconsistent);
            seed(patient, colleague, null, date.plusDays(5), "11:30");
            return null;
        });
        jdbc.update("update tb_agendamento set version=7 where hora_atendimento='08:00:00'");
        jdbc.execute("SCRIPT TO '/tmp/sibraps-etapa2-fluxos/legacy-agenda-v0.raw.sql'");
    }
}
