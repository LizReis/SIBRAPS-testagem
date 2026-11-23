package com.pet.buscaativa.services.impl;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.DecisaoRevisaoProvenienciaRepository;
import com.pet.buscaativa.repositories.FilaPendenciasRevisaoProvenienciaRepository;

import lombok.RequiredArgsConstructor;

/**
 * Contadores INTERNOS e read-only de uma unidade para a futura aba de
 * Relatorios. Diferente do resumo por paciente, este nao recebe UUID nem
 * unidade como entrada: a chave vem do administrador autenticado.
 *
 * Os dois indicadores nao sao indicadores clinicos de frequencia/risco e
 * nao criam autorizacao para ver prontuarios ou fatos de outras unidades.
 * Sem controller, migration, backfill nem mutacao de solicitacao V12/V13.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResumoUnidadeRevisaoProvenienciaService {
    private final EscopoUnidadeService escopo;
    private final FilaPendenciasRevisaoProvenienciaRepository fila;
    private final DecisaoRevisaoProvenienciaRepository decisoes;

    public record Resumo(long pendenciasAbertas, long decisoesRegistradas) {}

    public Resumo consultar() {
        var usuario = escopo.usuarioLogado();
        if (usuario == null || usuario.getTipoUsuario() != TipoUsuario.ADMINISTRADOR)
            throw new AccessDeniedException("Perfil sem permissao para o resumo de revisoes.");
        escopo.validarUnidadeConfigurada();
        String unidade = ChaveUnidadeAssistencial.doUsuarioAtual(usuario);
        long abertas = fila.contarAbertasDaUnidade(unidade, Estado.PENDENTE);
        long decididas = decisoes.contarDecisoesDaUnidade(unidade);
        return new Resumo(abertas, decididas);
    }
}
