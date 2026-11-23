package com.pet.buscaativa.services.impl;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia;
import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia.Decisao;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.DecisaoRevisaoProvenienciaRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * Consulta INTERNA, somente leitura, da deliberacao V13 sobre um ticket.
 * Destinada a caracterizar o futuro historico auditavel dos Relatorios.
 *
 * Nao e rota HTTP e nao modifica solicitacoes V12, fatos clinicos ou
 * estados de revisao. A fila aberta continua sendo consulta separada.
 * Exigir perfil administrativo, identidade global com vinculo EXPLICITO
 * da unidade logada e ticket pertencente a esse mesmo paciente/unidade.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsultaDecisaoRevisaoProvenienciaService {
    private final EscopoUnidadeService escopo;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final DecisaoRevisaoProvenienciaRepository decisoes;

    /** Dados minimos da decisao; nao expor usuario revisor ou entidade JPA. */
    public record Detalhe(UUID id, UUID pendenciaId, Decisao decisao,
            String justificativa, Instant decididaEm) {}

    public Detalhe detalhar(UUID pacienteIdPublico, UUID pendenciaId) {
        var ator = escopo.usuarioLogado();
        if (ator == null || ator.getTipoUsuario() != TipoUsuario.ADMINISTRADOR)
            throw new AccessDeniedException("Perfil sem permissao para consultar decisoes.");
        escopo.validarUnidadeConfigurada();
        if (pacienteIdPublico == null || pendenciaId == null)
            throw naoEncontrada();

        String unidade = ChaveUnidadeAssistencial.doUsuarioAtual(ator);
        var vinculo = autorizacao.exigirVinculoLocal(pacienteIdPublico);
        if (vinculo == null || vinculo.getPaciente() == null
                || vinculo.getPaciente().getId() == null
                || !Objects.equals(unidade, vinculo.getUnidadeChave()))
            throw naoEncontrada();

        return decisoes.findByPendenciaIdAndPacienteIdAndUnidadeChave(
                pendenciaId, vinculo.getPaciente().getId(), unidade)
                .map(ConsultaDecisaoRevisaoProvenienciaService::resumir)
                .orElseThrow(ConsultaDecisaoRevisaoProvenienciaService::naoEncontrada);
    }

    private static Detalhe resumir(DecisaoRevisaoProveniencia evento) {
        return new Detalhe(evento.getId(), evento.getPendencia().getId(),
                evento.getDecisao(), evento.getJustificativa(), evento.getDecididaEm());
    }

    private static ResourceNotFoundException naoEncontrada() {
        return new ResourceNotFoundException("Decisao nao encontrada.");
    }
}
