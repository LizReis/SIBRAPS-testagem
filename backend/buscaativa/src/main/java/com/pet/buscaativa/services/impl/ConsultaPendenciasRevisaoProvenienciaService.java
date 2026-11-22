package com.pet.buscaativa.services.impl;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Situacao;
import com.pet.buscaativa.repositories.PendenciaRevisaoProvenienciaRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * Leitura interna de solicitacoes pendentes da propria unidade.
 *
 * Nao e um endpoint; nao habilita painel ou criacao de tickets. O UUID
 * global do paciente e o ID da pendencia nao sao provas de autorizacao.
 * A unidade usada na consulta vem EXCLUSIVAMENTE do vinculo verificado
 * para o usuario autenticado, nunca de um parametro do cliente.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsultaPendenciasRevisaoProvenienciaService {
    private final EscopoUnidadeService escopo;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final PendenciaRevisaoProvenienciaRepository pendencias;

    /** Nao expoe a entidade JPA nem o solicitante ou outros vinculos. */
    public record Resumo(
            UUID id,
            TipoRegistro tipoRegistro,
            Long registroId,
            Situacao situacaoEvidencia,
            String justificativa,
            Instant solicitadoEm,
            Estado estado) {}

    public Page<Resumo> listar(UUID pacienteIdPublico, Pageable pagina) {
        exigirPerfilAssistencial();
        if (pagina == null || !pagina.isPaged() || pagina.getPageSize() < 1
                || pagina.getPageSize() > 100)
            throw new IllegalArgumentException("Paginacao limitada a 1..100 registros.");
        VinculoAssistencial vinculo = exigirVinculo(pacienteIdPublico);
        return pendencias.findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                vinculo.getPaciente().getId(), vinculo.getUnidadeChave(),
                Estado.PENDENTE, pagina).map(ConsultaPendenciasRevisaoProvenienciaService::resumir);
    }

    public Resumo detalhar(UUID pacienteIdPublico, UUID pendenciaId) {
        exigirPerfilAssistencial();
        if (pendenciaId == null)
            throw naoEncontrada();
        VinculoAssistencial vinculo = exigirVinculo(pacienteIdPublico);
        return pendencias.findByIdAndPacienteIdAndUnidadeChave(
                pendenciaId, vinculo.getPaciente().getId(), vinculo.getUnidadeChave())
                .map(ConsultaPendenciasRevisaoProvenienciaService::resumir)
                .orElseThrow(ConsultaPendenciasRevisaoProvenienciaService::naoEncontrada);
    }

    private void exigirPerfilAssistencial() {
        var usuario = escopo.usuarioLogado();
        TipoUsuario perfil = usuario == null ? null : usuario.getTipoUsuario();
        if (perfil != TipoUsuario.ADMINISTRADOR && perfil != TipoUsuario.PROFISSIONAL)
            throw new AccessDeniedException("Perfil sem permissao para consultar pendencias.");
    }

    private VinculoAssistencial exigirVinculo(UUID pacienteIdPublico) {
        VinculoAssistencial vinculo = autorizacao.exigirVinculoLocal(pacienteIdPublico);
        if (vinculo == null || vinculo.getPaciente() == null
                || vinculo.getPaciente().getId() == null
                || vinculo.getUnidadeChave() == null
                || vinculo.getUnidadeChave().isBlank())
            throw naoEncontrada();
        return vinculo;
    }

    private static Resumo resumir(PendenciaRevisaoProveniencia pendencia) {
        return new Resumo(
                pendencia.getId(),
                pendencia.getTipoRegistro(),
                pendencia.getRegistroId(),
                pendencia.getSituacaoEvidencia(),
                pendencia.getJustificativa(),
                pendencia.getSolicitadoEm(),
                pendencia.getEstado());
    }

    private static ResourceNotFoundException naoEncontrada() {
        return new ResourceNotFoundException("Pendencia nao encontrada.");
    }
}
