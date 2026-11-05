package com.pet.buscaativa.services;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import com.pet.buscaativa.entities.Agendamento;

/** Missed replacement of a missed appointment, without intervening/later attendance. */
@Component
public class PoliticaVisitaDomiciliar {

    public boolean necessaria(List<Agendamento> atendimentos, LocalDateTime ultimaPresenca) {
        return atendimentos.stream().anyMatch(a -> {
            var original = a.getAgendamentoOriginal();
            if (a.isMigracaoPendente() || a.getFrequenciaAssistencial() != com.pet.buscaativa.entities.enums.FrequenciaAssistencial.FALTOU || !a.isRemarcacaoAposFalta()
                    || original == null || a.getPaciente() == null || original.getPaciente() == null
                    || !Objects.equals(a.getPaciente().getId(), original.getPaciente().getId())) return false;
            var quando = instante(a); var antes = instante(original);
            return quando != null && antes != null && antes.isBefore(quando)
                    && (ultimaPresenca == null || (antes.isAfter(ultimaPresenca) && quando.isAfter(ultimaPresenca)))
                    && originalEraFalta(original);
        });
    }

    private boolean originalEraFalta(Agendamento original) {
        return !original.isMigracaoPendente()
                && original.getFrequenciaAssistencial() == com.pet.buscaativa.entities.enums.FrequenciaAssistencial.FALTOU;
    }

    private static LocalDateTime instante(Agendamento a) {
        return a.getDataAgendamento() == null || a.getHoraAtendimento() == null ? null
                : LocalDateTime.of(a.getDataAgendamento(), a.getHoraAtendimento());
    }
}
