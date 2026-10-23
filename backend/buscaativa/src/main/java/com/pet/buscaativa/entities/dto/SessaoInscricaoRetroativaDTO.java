package com.pet.buscaativa.entities.dto;

import java.time.LocalDate; 
import java.time.LocalTime;
import com.pet.buscaativa.entities.enums.*;

public record SessaoInscricaoRetroativaDTO(
    Long sessaoId, 
    LocalDate data, 
    LocalTime horario, 
    StatusSessaoGrupo status,
 
    StatusExibicaoSessaoGrupo statusExibicao, 
    boolean necessitaFrequencia
) {}