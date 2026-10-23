package com.pet.buscaativa.controllers;

import java.util.List;
import java.util.UUID;

import com.pet.buscaativa.entities.dto.DisponibilidadeExcecaoDTO;
import com.pet.buscaativa.services.DisponibilidadeExcecaoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/disponibilidade-config/excecoes")
@RequiredArgsConstructor
public class DisponibilidadeExcecaoController {

    private final DisponibilidadeExcecaoService excecaoService;

    private String getEmailLogado() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @PostMapping
    public ResponseEntity<DisponibilidadeExcecaoDTO> salvar(@RequestBody @Valid DisponibilidadeExcecaoDTO dto) {
        return ResponseEntity.ok(excecaoService.save(dto, getEmailLogado()));
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @GetMapping
    public ResponseEntity<List<DisponibilidadeExcecaoDTO>> listar(@RequestParam(required = false) UUID usuarioId) {
        return ResponseEntity.ok(excecaoService.listar(getEmailLogado(), usuarioId));
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletar(@PathVariable Long id) {
        excecaoService.deletar(id, getEmailLogado());
        return ResponseEntity.noContent().build();
    }
}