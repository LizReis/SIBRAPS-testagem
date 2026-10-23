package com.pet.buscaativa.services;

import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;

public interface PainelService {
    PainelBuscaAtivaDTO buscarResumo(int periodoMeses, String unidade,
            TipoAcompanhamento tipoAcompanhamento, Boolean situacaoRua);
}
