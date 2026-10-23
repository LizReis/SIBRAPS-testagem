package com.pet.buscaativa.entities.dto;
import java.time.LocalTime;
import java.util.List;

import com.pet.buscaativa.entities.enums.TurnoEnum;
public record HorariosDisponiveisDTO(TurnoEnum turno, int vagasRestantesTurno,
        String motivoIndisponibilidade, List<HorarioDisponivelDTO> horarios) {
    public record HorarioDisponivelDTO(LocalTime hora, boolean disponivel) {}
}