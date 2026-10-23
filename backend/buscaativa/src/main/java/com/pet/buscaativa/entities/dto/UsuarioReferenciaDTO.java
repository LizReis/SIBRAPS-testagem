package com.pet.buscaativa.entities.dto;

import java.util.UUID;

import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;

/** Representação pública mínima, sem credenciais, de um usuário de referência. */
public record UsuarioReferenciaDTO(UUID idPublico, String nome, UnidadeAtuacao unidadeAtuacao) {
    public UsuarioReferenciaDTO(Usuario usuario) {
        this(usuario.getIdPublico(), usuario.getNome(), usuario.getUnidadeAtuacao());
    }
}