package com.pet.buscaativa.entities.dto;

import java.util.UUID;

import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;

public record ProfissionalSelecaoDTO(
        UUID idPublico,
        String nome,
        UnidadeAtuacao unidadeAtuacao) {

    public ProfissionalSelecaoDTO(Usuario usuario) {
        this(usuario.getIdPublico(), usuario.getNome(), usuario.getUnidadeAtuacao());
    }
}