package com.pet.buscaativa.services;

import java.util.List;
import java.util.UUID;

import com.pet.buscaativa.entities.dto.DisponibilidadeExcecaoDTO;

public interface DisponibilidadeExcecaoService {

    DisponibilidadeExcecaoDTO save(DisponibilidadeExcecaoDTO dto, String emailLogado);

    List<DisponibilidadeExcecaoDTO> listar(String emailLogado, UUID usuarioIdPublico);

    void deletar(Long id, String emailLogado);
}