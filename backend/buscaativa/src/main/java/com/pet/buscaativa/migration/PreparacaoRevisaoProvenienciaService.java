package com.pet.buscaativa.migration;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Evidencia;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Situacao;
import com.pet.buscaativa.services.PoliticaJustificativaCorrecao;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.AutorizacaoVinculoAssistencialService;

import lombok.RequiredArgsConstructor;

/**
 * PREPARACAO APENAS EM MEMORIA de uma solicitacao de revisao de proveniencia.
 *
 * Nao consulta registros clinicos por id, nao persiste pendencia/auditoria,
 * nao altera origem de fatos, nao autoriza acesso clinico e nao e endpoint.
 * O contrato futuro de persistencia exigira verificacao independente do fato,
 * autor e permissao de revisao, estado de origem e controle de concorrencia.
 * Nao usar metadados fornecidos pelo cliente como prova.
 */
@Service
@RequiredArgsConstructor
public class PreparacaoRevisaoProvenienciaService {
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final PoliticaJustificativaCorrecao justificativas;

    public enum Estado { RASCUNHO_NAO_PERSISTIDO }

    public record Rascunho(
            Estado estado,
            Situacao evidenciasParaRevisar,
            String unidadeDaSolicitacao,
            String justificativaNormalizada) {}

    @Transactional(readOnly = true)
    public Rascunho preparar(UUID pacienteId,
            Evidencia evidenciaVerificadaInternamente,
            String justificativaInformada) {
        // Primeiramente exigir vinculo explicito. Uma referencia cadastral
        // da pessoa ou a lotacao atual do profissional nao substitui vinculo.
        String unidade = autorizacao.exigirVinculoLocal(pacienteId).getUnidadeChave();
        Situacao situacao = ClassificadorEvidenciaClinicaLocal.classificar(
                unidade, Objects.requireNonNull(evidenciaVerificadaInternamente));

        // Fato de outra unidade, ou fonte que conflita com essa unidade,
        // nunca pode ser submetido por essa via de revisao LOCAL.
        if (situacao == Situacao.FORA_DA_UNIDADE
                || situacao == Situacao.REVISAR_ORIGENS_CONFLITANTES)
            throw new ResourceNotFoundException("Registro não encontrado.");

        // Reutilizar a politica vigente: 10..2000 caracteres, sem justificativa
        // generica. Validar o texto nao equivale a autorizar a origem.
        String explicacao = justificativas.validar(justificativaInformada);
        return new Rascunho(Estado.RASCUNHO_NAO_PERSISTIDO, situacao,
                unidade, explicacao);
    }
}
