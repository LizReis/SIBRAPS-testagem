package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.PendenciaRevisaoProvenienciaComandoRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.PoliticaJustificativaCorrecao;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * Primeiro caminho INTERNO de persistencia de solicitacao V12.
 *
 * NAO e endpoint, nao corrige prontuario, nao reclassifica fatos anteriores
 * nem faz backfill de origem. So aceita fatos cuja origem LOCAL e paciente
 * foram comprovados pelo verificador com dados carregados do servidor.
 *
 * Fatos historicos com proveniencia desconhecida continuam fora desta via:
 * a revisao humana desses casos necessita procedimento de evidencia proprio.
 */
@Service
@RequiredArgsConstructor
public class SolicitacaoRevisaoProvenienciaService {
    private final EscopoUnidadeService escopo;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final VerificacaoFatoRevisaoProvenienciaService verificador;
    private final PoliticaJustificativaCorrecao politicaJustificativa;
    private final VinculoAssistencialRepository vinculos;
    private final PendenciaRevisaoProvenienciaComandoRepository pendencias;
    private final Clock clock;

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
    public VerificacaoFatoRevisaoProvenienciaService.CandidatosOrigem candidatosOrigem(UUID paciente,UUID vinculo) {
        return verificador.candidatosOrigem(paciente,vinculo);
    }

    @Transactional
    public UUID solicitar(UUID pacienteIdPublico, TipoRegistro tipoRegistro,
            Long registroId, String justificativa) {
        var ator = escopo.usuarioLogado();
        if (ator == null || (ator.getTipoUsuario() != TipoUsuario.ADMINISTRADOR
                && ator.getTipoUsuario() != TipoUsuario.PROFISSIONAL))
            throw new AccessDeniedException("Perfil sem permissao para solicitar revisao.");

        // Esta conferência executa as regras de origem, titularidade e
        // participacao na sessao sem confiar em atributos do cliente.
        var fato = verificador.verificar(pacienteIdPublico, tipoRegistro, registroId);
        String explicacao = politicaJustificativa.validar(justificativa);
        VinculoAssistencial vinculo = autorizacao.exigirVinculoLocal(pacienteIdPublico);
        if (vinculo == null || vinculo.getId() == null
                || vinculo.getPaciente() == null
                || !Objects.equals(fato.pacienteId(), vinculo.getPaciente().getId())
                || !Objects.equals(fato.unidadeChave(), vinculo.getUnidadeChave())
                || fato.tipoRegistro() != tipoRegistro
                || !Objects.equals(fato.registroId(), registroId))
            throw naoEncontrado();

        // Serializa duas requisicoes pelo mesmo vinculo dentro da transacao,
        // evitando dois tickets simultaneos do mesmo fato pela aplicacao.
        VinculoAssistencial bloqueado = vinculos.findByIdForPendenciaUpdate(vinculo.getId())
                .orElseThrow(SolicitacaoRevisaoProvenienciaService::naoEncontrado);
        if (bloqueado.getPaciente() == null
                || !Objects.equals(fato.pacienteId(), bloqueado.getPaciente().getId())
                || !Objects.equals(fato.unidadeChave(), bloqueado.getUnidadeChave()))
            throw naoEncontrado();

        if (pendencias.existsByPacienteIdAndUnidadeChaveAndTipoRegistroAndRegistroId(
                fato.pacienteId(), fato.unidadeChave(), tipoRegistro, registroId))
            throw new ValidationException("Ja existe solicitacao para este registro nesta unidade.");

        PendenciaRevisaoProveniencia pendencia =
                fato.historicoSemOrigemComFonteLocal()
                        ?PendenciaRevisaoProveniencia.paraHistoricoSemOrigemComFonteLocal(
                            bloqueado,registroId,explicacao,ator,Instant.now(clock))
                        :PendenciaRevisaoProveniencia.paraFatoLocalComprovado(
                            bloqueado,tipoRegistro,registroId,explicacao,ator,Instant.now(clock));
        pendencias.save(pendencia);
        // O registro append-only e uma SOLICITACAO, nunca autorizacao clinica.
        return pendencia.getId();
    }

    private static ResourceNotFoundException naoEncontrado() {
        return new ResourceNotFoundException("Registro nao encontrado.");
    }
}
