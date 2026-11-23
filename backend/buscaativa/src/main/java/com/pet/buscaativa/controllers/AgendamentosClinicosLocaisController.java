package com.pet.buscaativa.controllers;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pet.buscaativa.entities.dto.AgendamentosClinicosLocaisDTO;
import com.pet.buscaativa.services.impl.ConsultaAgendamentosClinicosLocaisService;

import lombok.RequiredArgsConstructor;

/**
 * Rota aditiva para consultar apenas agendamentos da propria unidade.
 * Nao substitui agendas, historicos e indicadores globais legados.
 */
@RestController
@RequestMapping("/api/pacientes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMINISTRADOR','PROFISSIONAL','RECEPCAO')")
public class AgendamentosClinicosLocaisController {
    private final ConsultaAgendamentosClinicosLocaisService consulta;

    @GetMapping("/{idPublico}/agendamentos-locais")
    public ResponseEntity<AgendamentosClinicosLocaisDTO> listar(
            @PathVariable UUID idPublico) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(consulta.consultar(idPublico));
    }
}
