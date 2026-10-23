package com.pet.buscaativa.alertas;

import java.time.LocalDate;

public record PacienteVermelhoDTO(
        String nome,
        Integer countFaltas,
        LocalDate dataUltimaPresenca
) {
}