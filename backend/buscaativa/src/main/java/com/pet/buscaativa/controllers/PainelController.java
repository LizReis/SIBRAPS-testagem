package com.pet.buscaativa.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.PainelService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/painel")
@RequiredArgsConstructor
public class PainelController {
    private final PainelService painelService;

    @GetMapping("/resumo")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    public ResponseEntity<PainelBuscaAtivaDTO> buscarResumo(
            @RequestParam(defaultValue = "6") int periodoMeses,
            @RequestParam(required = false) String unidade,
            @RequestParam(required = false) TipoAcompanhamento tipoAcompanhamento,
            @RequestParam(required = false) Boolean situacaoRua) {
        return ResponseEntity.ok(painelService.buscarResumo(periodoMeses, unidade, tipoAcompanhamento, situacaoRua));
    }
}
