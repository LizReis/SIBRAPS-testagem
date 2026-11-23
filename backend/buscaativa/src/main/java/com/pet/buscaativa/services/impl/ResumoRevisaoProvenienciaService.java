package com.pet.buscaativa.services.impl;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Resumo INTERNO para a futura area de Relatorios, somente leitura.
 *
 * Usa as duas consultas ja autorizadas por paciente e unidade autenticada.
 * A consulta de decisoes exige ADMINISTRADOR e vinculo local explicito;
 * ela vem primeiro para nunca conceder a profissionais o resumo agregado.
 *
 * Nao libera rota HTTP, nao deduz unidade pela referencia cadastral, nao
 * consulta prontuario e nao altera pendencias V12 nem decisoes V13.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResumoRevisaoProvenienciaService {
    private final ConsultaDecisaoRevisaoProvenienciaService decisoes;
    private final ConsultaPendenciasRevisaoProvenienciaService pendencias;

    public record Resumo(long pendenciasAbertas, long decisoesRegistradas) {}

    public Resumo consultar(UUID pacienteIdPublico) {
        // PageRequest minimo: os totais vem da consulta COUNT escopada;
        // nao carregar a lista inteira de tickets ou de decisoes.
        var paginaMinima = PageRequest.of(0, 1);
        long totalDecisoes = decisoes.listar(pacienteIdPublico, paginaMinima)
                .getTotalElements();
        long totalAbertas = pendencias.listar(pacienteIdPublico, paginaMinima)
                .getTotalElements();
        return new Resumo(totalAbertas, totalDecisoes);
    }
}
