package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.util.UUID;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.enums.*;

public record PacienteListaDTO(UUID idPublico, String nome, String cpf, String cns,
        String unidade, int countFaltas, ClassificacaoRisco classificacaoRisco,
        LocalDate dataUltimaPresenca, StatusPaciente statusPaciente,
        TipoAcompanhamento tipoAcompanhamento) {
    public PacienteListaDTO(Paciente p) {
        this(p.getIdPublico(), p.getNome(), p.getCpf(), p.getCns(),
                p.getUsfReferencia() == null ? null : p.getUsfReferencia().getNomeUsf(),
                p.getCountFaltas(), p.getClassificacaoRisco(), p.getDataUltimaPresenca(),
                p.getStatusPaciente(), p.getTipoAcompanhamento());
    }
}