package com.pet.buscaativa.controllers;

import java.util.List;
import java.util.UUID;

import com.pet.buscaativa.entities.dto.DisponibilidadeDTO;
import com.pet.buscaativa.services.DisponibilidadeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/disponibilidade-config")
@RequiredArgsConstructor
public class DisponibilidadeController {

    private final DisponibilidadeService disponibilidadeService;

    private String getEmailLogado() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @PostMapping("/disponibilidade")
    public ResponseEntity<DisponibilidadeDTO> salvarDisponibilidade(@RequestBody @Valid DisponibilidadeDTO disponibilidadeDTO) {
        return ResponseEntity.ok(disponibilidadeService.save(disponibilidadeDTO, getEmailLogado()));
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @GetMapping("/disponibilidade")
    public ResponseEntity<List<DisponibilidadeDTO>> listarDisponibilidades(@RequestParam(required = false) UUID usuarioId) {
        return ResponseEntity.ok(disponibilidadeService.listarDisponibilidades(getEmailLogado(), usuarioId));
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @DeleteMapping("/disponibilidade/{id}")
    public ResponseEntity<Void> deletarDisponibilidade(@PathVariable Long id) {
        disponibilidadeService.deletarDisponibilidade(id, getEmailLogado());
        return ResponseEntity.noContent().build();
    }
}