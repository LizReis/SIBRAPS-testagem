package com.pet.buscaativa.services;

import java.time.LocalDate;
import java.util.UUID;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;

public interface RelatorioService {
    byte[] gerarBuscaAtiva(LocalDate dataInicio, LocalDate dataFim, UUID profissionalId,
                           TipoAcompanhamento tipoAcompanhamento);
    byte[] gerarFrequenciaGrupos(LocalDate dataInicio, LocalDate dataFim, Long grupoId);

    byte[] gerarBuscaAtivaExcel(LocalDate dataInicio, LocalDate dataFim, UUID profissionalId,
                                TipoAcompanhamento tipoAcompanhamento);
    byte[] gerarFrequenciaGruposExcel(LocalDate dataInicio, LocalDate dataFim, Long grupoId);
}