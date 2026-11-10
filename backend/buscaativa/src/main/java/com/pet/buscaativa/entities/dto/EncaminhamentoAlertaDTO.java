package com.pet.buscaativa.entities.dto;
import java.util.UUID;
import jakarta.validation.constraints.*;
/** Formal registration of an explicit action; does not claim delivery to an external service. */
public record EncaminhamentoAlertaDTO(@NotNull UUID idRequisicao,@NotBlank @Size(max=200) String destino,
        @NotBlank @Size(min=10,max=1000) String descricao) {}
