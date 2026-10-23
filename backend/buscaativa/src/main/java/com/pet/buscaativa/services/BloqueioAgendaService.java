package com.pet.buscaativa.services;

import java.util.List;
import java.util.UUID;

import com.pet.buscaativa.entities.dto.BloqueioAgendaDTO;

public interface BloqueioAgendaService {

    BloqueioAgendaDTO save(BloqueioAgendaDTO bloqueioAgendaDTO, String emailLogado);

    List<BloqueioAgendaDTO> listarBloqueios(String emailLogado, UUID usuarioIdPublico);

    void deletarBloqueio(Long id, String emailLogado);
}