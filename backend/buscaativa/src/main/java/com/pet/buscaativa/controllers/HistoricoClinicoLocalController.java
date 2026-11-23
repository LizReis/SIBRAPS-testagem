package com.pet.buscaativa.controllers;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pet.buscaativa.entities.dto.HistoricoClinicoLocalDTO;
import com.pet.buscaativa.services.impl.ConsultaHistoricoClinicoLocalService;

import lombok.RequiredArgsConstructor;

/**
 * Consulta dedicada ao futuro historico local; ainda nao substitui a UI nem
 * as operacoes clinicas legadas. Somente eventos com proveniencia verificavel,
 * nunca indicadores globais de uma identidade compartilhada.
 */
@RestController
@RequestMapping("/api/pacientes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMINISTRADOR','PROFISSIONAL','RECEPCAO')")
public class HistoricoClinicoLocalController {
    private final ConsultaHistoricoClinicoLocalService consulta;

    @GetMapping("/{idPublico}/historico-local")
    public ResponseEntity<HistoricoClinicoLocalDTO> historicoLocal(
            @PathVariable UUID idPublico) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(consulta.consultar(idPublico));
    }
}
