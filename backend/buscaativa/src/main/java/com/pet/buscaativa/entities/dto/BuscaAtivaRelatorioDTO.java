package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BuscaAtivaRelatorioDTO {
    private static final DateTimeFormatter DATA_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final String nomeCompleto;
    private final String cnsCpf;
    private final LocalDate dataUltimoAtendimento;
    private final Integer faltasConsecutivas;
    private final String tipoAcompanhamento;
    private final String profissionalReferencia;
    private final String telefone;

    public String getDataUltimoAtendimentoFormatada() {
        return dataUltimoAtendimento == null ? "Não informado" : dataUltimoAtendimento.format(DATA_BR);
    }
}
