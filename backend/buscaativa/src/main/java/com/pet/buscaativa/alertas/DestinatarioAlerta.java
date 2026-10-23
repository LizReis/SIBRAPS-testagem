package com.pet.buscaativa.alertas;

import java.util.List;

public record DestinatarioAlerta(
        String nome,
        String email,
        List<PacienteVermelhoDTO> pacientes
) {
}