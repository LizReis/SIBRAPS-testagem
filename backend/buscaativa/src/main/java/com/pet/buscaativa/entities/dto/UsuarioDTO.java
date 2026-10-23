package com.pet.buscaativa.entities.dto;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UsuarioDTO(
        UUID idPublico,

        @NotBlank
        @NotNull(message = "Digite o nome do profissional.")
        String nome,

        @NotBlank
        @NotNull(message = "O campo de e-mail não pode ficar vazio.")
        String email,

        @NotNull(message = "O tipo de usuário deve ser selecionado.")
        TipoUsuario tipoUsuario,

        @NotNull(message = "A unidade de atuação deve ser informada.")
        UnidadeAtuacao unidadeAtuacao,

        Long usfAtuacaoId,

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        UsfAtuacaoDTO usfAtuacao,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Size(min = 6, message = "A senha deve ter pelo menos 6 caracteres.")
        String senha) {

    public UsuarioDTO(Usuario entity) {
        this(
                entity.getIdPublico(),
                entity.getNome(),
                entity.getEmail(),
                entity.getTipoUsuario(),
                entity.getUnidadeAtuacao(),
                entity.getUsfAtuacao() == null ? null : entity.getUsfAtuacao().getId(),
                entity.getUsfAtuacao() == null ? null : new UsfAtuacaoDTO(entity.getUsfAtuacao()),
                null
        );
    }
}