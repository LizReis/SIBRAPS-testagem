package com.pet.buscaativa.services.impl;

import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.FilaPendenciasRevisaoProvenienciaRepository;
import com.pet.buscaativa.services.PoliticaJustificativaCorrecao;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * PREPARACAO INTERNA E SOMENTE EM MEMORIA de uma decisao humana sobre V12.
 *
 * Nao e endpoint, nao altera a pendencia original (append-only), nao cria
 * auditoria/decisao persistente e nunca modifica a proveniencia clinica.
 * Somente o administrador autenticado pode preparar uma decisao para ticket
 * PENDENTE da sua propria unidade canonica. Um UUID nao concede acesso.
 *
 * A futura persistencia exigira revisao de evidencia, trilha append-only,
 * validacao do revisor e uma transacao separada e testada.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PreparacaoDecisaoRevisaoProvenienciaService {
    private final EscopoUnidadeService escopo;
    private final FilaPendenciasRevisaoProvenienciaRepository fila;
    private final PoliticaJustificativaCorrecao justificativas;

    public enum Decisao { APROVAR, REJEITAR }
    public enum EstadoRascunho { RASCUNHO_NAO_PERSISTIDO }

    public record Rascunho(
            EstadoRascunho estado,
            UUID pendenciaId,
            String unidadeChave,
            Decisao decisaoProposta,
            String justificativaNormalizada) {}

    public Rascunho preparar(UUID pendenciaId, Decisao decisao, String justificativaInformada) {
        var usuario = escopo.usuarioLogado();
        if (usuario == null || usuario.getTipoUsuario() != TipoUsuario.ADMINISTRADOR)
            throw new AccessDeniedException("Perfil sem permissao para revisar pendencias.");
        escopo.validarUnidadeConfigurada();
        String unidade = ChaveUnidadeAssistencial.doUsuarioAtual(usuario);

        if (pendenciaId == null)
            throw naoEncontrada();
        if (decisao == null)
            throw new IllegalArgumentException("Decisao proposta obrigatoria.");

        // Uma solicitacao V12 e imutavel e permanece PENDENTE mesmo
        // depois da decisao V13. Preparar SOMENTE tickets sem decisao;
        // a consulta bruta e reservada ao comando que verifica duplicatas.
        PendenciaRevisaoProveniencia pendencia =
                fila.findAbertaByIdAndUnidadeChaveAndEstado(
                        pendenciaId, unidade, Estado.PENDENTE)
                    .orElseThrow(PreparacaoDecisaoRevisaoProvenienciaService::naoEncontrada);
        if (!Objects.equals(pendenciaId, pendencia.getId())
                || !Objects.equals(unidade, pendencia.getUnidadeChave())
                || pendencia.getEstado() != Estado.PENDENTE)
            throw naoEncontrada();

        String motivo = justificativas.validar(justificativaInformada);
        // Esta aprovacao e apenas uma PROPOSTA de decisao, nao a aprovacao de
        // um fato clinico nem uma migracao automatica de sua unidade historica.
        return new Rascunho(EstadoRascunho.RASCUNHO_NAO_PERSISTIDO,
                pendencia.getId(), unidade, decisao, motivo);
    }

    private static ResourceNotFoundException naoEncontrada() {
        return new ResourceNotFoundException("Pendencia nao encontrada.");
    }
}
