package com.pet.buscaativa.services;

import java.time.YearMonth;
import java.util.List;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO.EvolucaoMensalDTO;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;

public interface PainelHistoricoService {
    List<EvolucaoMensalDTO> reconstruir(List<YearMonth> meses, String unidade,
            TipoAcompanhamento tipoAcompanhamento, Boolean situacaoRua);

    boolean correspondeAosFiltros(Paciente paciente, String unidade,
            TipoAcompanhamento tipoAcompanhamento, Boolean situacaoRua);
}
