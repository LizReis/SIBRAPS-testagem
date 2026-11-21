package com.pet.buscaativa.services.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.repositories.ConsultaClinicaLocalRepository;

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

    public record ResumoInterno(List<Long> agendamentos, List<Long> historicos,
            List<Long> sessoesComParticipacao) {}

    @Transactional(readOnly = true)
    public ResumoInterno consultarIdentificadoresLocais(UUID pacienteId) {
        VinculoAssistencial vinculo = autorizacao.exigirVinculoLocal(pacienteId);
        String unidade = vinculo.getUnidadeChave();
        return new ResumoInterno(
                List.copyOf(consultas.agendamentos(pacienteId, unidade)),
                List.copyOf(consultas.historicos(pacienteId, unidade)),
                List.copyOf(consultas.sessoesComParticipacao(pacienteId, unidade)));
    }
}
