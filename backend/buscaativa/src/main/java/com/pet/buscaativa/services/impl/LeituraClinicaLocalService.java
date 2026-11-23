package com.pet.buscaativa.services.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.ConsultaClinicaLocalRepository;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.repositories.SessaoGrupoRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * Prova do futuro fluxo de leitura LOCAL; ainda sem controlador HTTP.
 * A chave da unidade provem sempre do vinculo explicitamente autorizado.
 * Nao usa CPF/CNS, referencias cadastrais ou lotacao atual de profissionais
 * antigos como filtro substituto da proveniencia clinica.
 *
 * IDs retornados permanecem internos; nao expor como API sem filtros de
 * acesso e contratos de DTOs apos o corte coordenado.
 */
@Service
@RequiredArgsConstructor
public class LeituraClinicaLocalService {
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final ConsultaClinicaLocalRepository consultas;
    private final AgendamentoRepository agendamentos;
    private final HistoricoPacienteRepository historicos;
    private final SessaoGrupoRepository sessoes;

    public record ResumoInterno(List<Long> agendamentos, List<Long> historicos,
            List<Long> sessoesComParticipacao) {}

    @Transactional(readOnly = true)
    public ResumoInterno consultarIdentificadoresLocais(UUID pacienteId) {
        VinculoAssistencial vinculo = autorizacao.exigirVinculoLocal(pacienteId);
        String unidade = vinculo.getUnidadeChave();
        // As consultas escalares sao apenas uma pre-selecao. Origem local
        // declarada no registro isolado NAO comprova remarcacoes ancestrais
        // nem coerencia entre o historico e o atendimento/sessao de origem.
        // Revalidar cada fato antes de sequer devolver identificadores.
        List<Long> agendasValidas = new ArrayList<>();
        for (Long id : consultas.agendamentos(pacienteId, unidade)) {
            var agenda = agendamentos.findById(id).orElse(null);
            if (agenda == null) continue;
            try {
                autorizacao.validarAgendamentoLocal(agenda);
                if (!agenda.isMigracaoPendente()
                        && agenda.getSituacaoReserva() != null
                        && agenda.getFrequenciaAssistencial() != null)
                    agendasValidas.add(id);
            } catch (ResourceNotFoundException origemNaoComprovada) {
                // Nao projetar fato historico sem cadeia integralmente local.
            }
        }

        List<Long> historicosValidos = new ArrayList<>();
        for (Long id : consultas.historicos(pacienteId, unidade)) {
            var historico = historicos.findById(id).orElse(null);
            if (historico == null) continue;
            try {
                autorizacao.validarHistoricoLocal(historico);
                // Eventos com fonte de agendamento tambem exigem estado
                // canonico nao pendente, assim como a agenda local HTTP.
                var fonte = historico.getAgendamento();
                if (fonte == null || !fonte.isMigracaoPendente()
                        && fonte.getSituacaoReserva() != null
                        && fonte.getFrequenciaAssistencial() != null)
                    historicosValidos.add(id);
            } catch (ResourceNotFoundException origemNaoComprovada) {
                // Nao projetar fonte externa, pendente ou incoerente.
            }
        }

        List<Long> sessoesValidas = new ArrayList<>();
        for (Long id : consultas.sessoesComParticipacao(pacienteId, unidade)) {
            var sessao = sessoes.findById(id).orElse(null);
            if (sessao == null) continue;
            try {
                autorizacao.validarSessaoLocal(sessao, vinculo.getPaciente());
                sessoesValidas.add(id);
            } catch (ResourceNotFoundException origemNaoComprovada) {
                // A FK/participacao isolada nao substitui autorizacao local.
            }
        }
        return new ResumoInterno(List.copyOf(agendasValidas),
                List.copyOf(historicosValidos), List.copyOf(sessoesValidas));
    }
}
