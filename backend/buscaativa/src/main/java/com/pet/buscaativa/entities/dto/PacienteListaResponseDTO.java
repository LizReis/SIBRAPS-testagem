package com.pet.buscaativa.entities.dto;
import java.util.List;
public record PacienteListaResponseDTO(PacienteResumoDTO resumo, List<PacienteListaDTO> pacientes,
        int paginaAtual, int tamanhoPagina, long totalRegistros, int totalPaginas) {}