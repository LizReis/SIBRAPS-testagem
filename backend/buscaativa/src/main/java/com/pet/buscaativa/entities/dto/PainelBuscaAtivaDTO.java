package com.pet.buscaativa.entities.dto;

import java.util.List;
import java.util.UUID;

import com.pet.buscaativa.entities.enums.ClassificacaoRisco;

public record PainelBuscaAtivaDTO(
        long totalPacientesAtivos,
        DistribuicaoClassificacaoDTO distribuicaoClassificacao,
        List<EvolucaoMensalDTO> evolucao,
        List<PacientePainelDTO> pacientesPrioritarios,
        List<UnidadePainelDTO> unidadesDisponiveis,
        boolean historicoDisponivel) {

    public record DistribuicaoClassificacaoDTO(
            long verdes, long amarelos, long vermelhos,
            double percentualVerdes, double percentualAmarelos, double percentualVermelhos) {
    }

    public record EvolucaoMensalDTO(
            String mes, String rotulo, long verdes, long amarelos, long vermelhos, boolean disponivel) {
    }

    public record PacientePainelDTO(
            UUID idPublico, String nome, Integer idade, ClassificacaoRisco classificacaoRisco,
            int quantidadeFaltas, String acaoNecessaria) {
    }

    public record UnidadePainelDTO(String valor, String nome) {
    }
}
