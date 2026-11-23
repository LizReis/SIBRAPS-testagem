package com.pet.buscaativa.services.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.dto.AgendamentosClinicosLocaisDTO;
import com.pet.buscaativa.entities.dto.AgendamentosClinicosLocaisDTO.AgendamentoLocalDTO;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * Consulta CLINICA local nova, SOMENTE LEITURA, separada do endpoint legado
 * de agendamentos. Nao utiliza referencia territorial nem indicadores globais.
 *
 * So retorna fatos com origem imutavel da propria unidade e estado canonico
 * coerente. Fatos legados sem origem ou em migracao pendente nao sao atribuidos
 * automaticamente a uma nova unidade.
 */
@Service
@RequiredArgsConstructor
public class ConsultaAgendamentosClinicosLocaisService {
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final AgendamentoRepository agendamentos;

    @Transactional(readOnly = true)
    public AgendamentosClinicosLocaisDTO consultar(UUID pacienteIdPublico) {
        var vinculo = autorizacao.exigirVinculoLocal(pacienteIdPublico);
        var paciente = vinculo.getPaciente();

        List<AgendamentoLocalDTO> locais = new ArrayList<>();
        for (Agendamento a : agendamentos.findComOrigemLocalComprovada(
                pacienteIdPublico, vinculo.getUnidadeChave())) {
            // Uma chave de origem igual nao e suficiente se o fato guarda
            // dados inconsistentes ou uma remarcacao ligada a outra unidade.
            if (a.isMigracaoPendente()
                    || a.getSituacaoReserva() == null
                    || a.getFrequenciaAssistencial() == null
                    || a.getUsuario() == null
                    || a.getPaciente() == null
                    || !Objects.equals(a.getPaciente().getId(), paciente.getId()))
                continue;

            // Nao basta validar o pai imediato. Uma remarcacao pode
            // apontar para uma cadeia antiga com origem NULL, outra unidade
            // ou migracao pendente. Qualquer elo incoerente bloqueia somente
            // esta projecao de leitura, sem modificar os registros.
            if (!cadeiaLocalComprovada(a, paciente.getId(),
                    vinculo.getUnidadeChave()))
                continue;

            try {
                autorizacao.validarAgendamentoLocal(a);
            } catch (ResourceNotFoundException inconsistente) {
                // Nao mostrar fato clinico cuja unidade nao foi comprovada.
                continue;
            }

            locais.add(new AgendamentoLocalDTO(a.getId(),
                    a.getDataAgendamento(), a.getHoraAtendimento(),
                    a.getUsuario().getNome(), a.getSituacaoReserva(),
                    a.getFrequenciaAssistencial()));
        }

        return new AgendamentosClinicosLocaisDTO(paciente.getNome(),
                locais.size(), List.copyOf(locais));
    }

    /**
     * Valida a cadeia de origem com limite de ciclos. A referencia do
     * profissional atual e a unidade territorial nao sao evidencias.
     * Uma cadeia incompleta, circular ou com outra identidade/unidade
     * nao deve contribuir para a agenda local.
     */
    private static boolean cadeiaLocalComprovada(Agendamento atual, Long pacienteId,
            String unidade) {
        var visitados = new java.util.HashSet<Long>();
        Agendamento elo = atual;
        while (elo != null) {
            if (elo.getId() == null || !visitados.add(elo.getId())
                    || elo.getPaciente() == null
                    || !Objects.equals(elo.getPaciente().getId(), pacienteId)
                    || !Objects.equals(elo.getUnidadeOrigemChave(), unidade)
                    || elo.isMigracaoPendente()
                    || elo.getSituacaoReserva() == null
                    || elo.getFrequenciaAssistencial() == null)
                return false;
            elo = elo.getAgendamentoOriginal();
        }
        return true;
    }
}
