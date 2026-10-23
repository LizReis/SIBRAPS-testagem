package com.pet.buscaativa.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pet.buscaativa.entities.dto.ResumoInicioDTO;
import com.pet.buscaativa.services.InicioService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/inicio")
@RequiredArgsConstructor
public class InicioController {

    private final InicioService inicioService;

    @GetMapping("/resumo")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    public ResponseEntity<ResumoInicioDTO> buscarResumo() {
        return ResponseEntity.ok(inicioService.buscarResumo());
    }
}