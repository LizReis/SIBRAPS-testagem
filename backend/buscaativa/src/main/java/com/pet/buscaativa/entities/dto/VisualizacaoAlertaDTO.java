package com.pet.buscaativa.entities.dto;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
/** The interface must call this only after actually opening/presenting the individual alert. */
public record VisualizacaoAlertaDTO(@NotNull UUID idRequisicao) {}
