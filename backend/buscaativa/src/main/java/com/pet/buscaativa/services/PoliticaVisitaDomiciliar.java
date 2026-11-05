package com.pet.buscaativa.services;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import lombok.RequiredArgsConstructor;

/** Missed replacement of a missed appointment, without intervening/later attendance. */
@Component
@RequiredArgsConstructor
public class PoliticaVisitaDomiciliar {
    private final HistoricoPacienteRepository historico;

    public boolean necessaria(List<Agendamento> atendimentos, LocalDateTime ultimaPresenca) {
        return atendimentos.stream().anyMatch(a -> {
            var original = a.getAgendamentoOriginal();
            if (a.getSituacaoAtendimento() != SituacaoAtendimento.FALTOU || !a.isRemarcacaoAposFalta()
                    || original == null || a.getPaciente() == null || original.getPaciente() == null
                    || !Objects.equals(a.getPaciente().getId(), original.getPaciente().getId())) return false;
            var quando = instante(a); var antes = instante(original);
            return quando != null && antes != null && antes.isBefore(quando)
                    && (ultimaPresenca == null || (antes.isAfter(ultimaPresenca) && quando.isAfter(ultimaPresenca)))
                    && originalEraFalta(original);
        });
    }

    private boolean originalEraFalta(Agendamento original) {
        var status = original.getSituacaoAtendimento();
        if (status == SituacaoAtendimento.PRESENTE || status == SituacaoAtendimento.FALTOU)
            return status == SituacaoAtendimento.FALTOU;
        if (status != SituacaoAtendimento.REMARCADO_ORIGEM) return false;
        return historico.findByAgendamento(original).stream()
                .filter(h -> h.getSituacaoAtendimento() == SituacaoAtendimento.PRESENTE
                        || h.getSituacaoAtendimento() == SituacaoAtendimento.FALTOU)
                .max(Comparator.comparing(PoliticaVisitaDomiciliar::alteradoEm).thenComparing(HistoricoPaciente::getId))
                .map(h -> h.getSituacaoAtendimento() == SituacaoAtendimento.FALTOU)
                // Caller verified same patient and chronology: retain recorded missed-reschedule provenance
                // only when no corrected clinical evidence survived. No clinical row is manufactured here.
                .orElse(true);
    }

    private static LocalDateTime alteradoEm(HistoricoPaciente h) {
        return h.getUpdatedAt() != null ? h.getUpdatedAt()
                : h.getCreatedAt() != null ? h.getCreatedAt() : h.getOcorridoEm();
    }
    private static LocalDateTime instante(Agendamento a) {
        return a.getDataAgendamento() == null || a.getHoraAtendimento() == null ? null
                : LocalDateTime.of(a.getDataAgendamento(), a.getHoraAtendimento());
    }
}
