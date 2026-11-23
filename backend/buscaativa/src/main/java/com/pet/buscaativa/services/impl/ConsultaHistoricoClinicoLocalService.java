package com.pet.buscaativa.services.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.dto.HistoricoClinicoLocalDTO;
import com.pet.buscaativa.entities.dto.HistoricoClinicoLocalDTO.EventoLocalDTO;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * Nova via SOMENTE DE LEITURA por vinculo local para historicos com origem
 * comprovada. Nao substitui a rota antiga ate migrar o restante da clinica.
 *
 * A autorizacao nao se baseia em capsReferencia/usfReferencia; nao considera
 * eventos sem origem nem adivinha a unidade a partir do profissional atual.
 * Nao devolve contadores, classificacao ou status globais do Paciente.
 */
@Service
@RequiredArgsConstructor
public class ConsultaHistoricoClinicoLocalService {
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final HistoricoPacienteRepository historicos;

    @Transactional(readOnly = true)
    public HistoricoClinicoLocalDTO consultar(UUID pacienteIdPublico) {
        // Nenhuma consulta clinica antes de exigir vinculo na unidade da
        // autenticacao. O servico nao cria vinculos nem modifica historicos.
        var vinculo = autorizacao.exigirVinculoLocal(pacienteIdPublico);
        var paciente = vinculo.getPaciente();

        // A consulta ao banco ja limita pela chave imutavel de proveniencia.
        // Fatos antigos NULL e de outras unidades nao entram no resultado.
        var candidatos = historicos.findLocaisComOrigemComprovada(
                pacienteIdPublico, vinculo.getUnidadeChave());

        List<HistoricoPaciente> validos = new ArrayList<>();
        for (var evento : candidatos) {
            // A origem declarada deve tambem coincidir com a fonte original
            // (agendamento/sessao). Uma fonte inconsistente nao e evidência
            // assistencial local; nao incluí-la na contagem nem na resposta.
            try {
                autorizacao.validarHistoricoLocal(evento);
                validos.add(evento);
            } catch (ResourceNotFoundException incoerente) {
                // Falha fechada: exclui somente o registro nao comprovado.
                // A fila de revisao humana trata proveniencias pendentes.
            }
        }

        List<EventoLocalDTO> eventos = validos.stream()
                .map(h -> new EventoLocalDTO(h.getId(), h.getTipo(),
                        h.getSituacaoAtendimento(), h.getOcorridoEm(),
                        h.getDescricao()))
                .toList();

        return new HistoricoClinicoLocalDTO(
                paciente.getNome(),
                eventos.size(),
                contar(validos, TipoEventoHistoricoPaciente.CONSULTA_AGENDADA),
                contar(validos, TipoEventoHistoricoPaciente.PRESENCA),
                contar(validos, TipoEventoHistoricoPaciente.FALTA),
                contar(validos, TipoEventoHistoricoPaciente.REMARCACAO),
                contar(validos, TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO),
                contar(validos, TipoEventoHistoricoPaciente.BUSCA_ATIVA),
                eventos);
    }

    private static long contar(List<HistoricoPaciente> eventos,
            TipoEventoHistoricoPaciente tipo) {
        return eventos.stream().filter(h -> h.getTipo() == tipo).count();
    }
}
