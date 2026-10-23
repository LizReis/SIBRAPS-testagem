package com.pet.buscaativa.services;

import java.util.List;
import java.util.UUID;

import com.pet.buscaativa.entities.dto.DisponibilidadeDTO;

public interface DisponibilidadeService {

    DisponibilidadeDTO save(DisponibilidadeDTO disponibilidadeDTO, String emailLogado);

    List<DisponibilidadeDTO> listarDisponibilidades(String emailLogado, UUID usuarioIdPublico);

    void deletarDisponibilidade(Long id, String emailLogado);
}