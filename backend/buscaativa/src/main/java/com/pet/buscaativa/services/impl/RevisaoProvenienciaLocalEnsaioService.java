package com.pet.buscaativa.services.impl;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia.Decisao;
import lombok.RequiredArgsConstructor;

/** Ensaios V12/V13 coordenados; o registro/decisão nunca aplica fato clínico. */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
public class RevisaoProvenienciaLocalEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final SolicitacaoRevisaoProvenienciaService solicitacoes;
    private final RegistroDecisaoRevisaoProvenienciaService decisoes;

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
    public VerificacaoFatoRevisaoProvenienciaService.CandidatosOrigem candidatosOrigem(UUID paciente,UUID vinculo) {
        atores.autorizar(true);
        return solicitacoes.candidatosOrigem(paciente,vinculo);
    }

    @Transactional
    public UUID solicitar(UUID paciente, UUID vinculo, Integer versao, TipoRegistro tipo, Long registro,
            String justificativa) {
        var recursos = coordenacao.paraVinculo(atores.autorizar(false), paciente, vinculo, null);
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, recursos.vinculos().get(paciente).getVersion());
        // Verificação original executada depois das travas de identidade,
        // ator e vínculo. Os escritores cooperantes da fonte exigem a identidade.
        return solicitacoes.solicitar(paciente, tipo, registro, justificativa);
    }

    @Transactional
    public UUID decidir(UUID paciente, UUID vinculo, Integer versao, UUID ticket, Decisao decisao,
            String justificativa) {
        var recursos = coordenacao.paraVinculo(atores.autorizar(true), paciente, vinculo, null);
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, recursos.vinculos().get(paciente).getVersion());
        return decisoes.registrar(paciente, ticket, decisao, justificativa);
    }
}
