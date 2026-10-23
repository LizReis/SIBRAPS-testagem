package com.pet.buscaativa.controllers;

import java.util.List;
import java.util.UUID;

import com.pet.buscaativa.entities.dto.BloqueioAgendaDTO;
import com.pet.buscaativa.services.BloqueioAgendaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bloqueioAgenda-config")
@RequiredArgsConstructor
public class BloqueioAgendaController {

    private final BloqueioAgendaService bloqueioAgendaService;

    private String getEmailLogado() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @PostMapping
    public ResponseEntity<BloqueioAgendaDTO> salvarBloqueio(@RequestBody @Valid BloqueioAgendaDTO bloqueioAgendaDTO) {
        return ResponseEntity.ok(bloqueioAgendaService.save(bloqueioAgendaDTO, getEmailLogado()));
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @GetMapping
    public ResponseEntity<List<BloqueioAgendaDTO>> listarBloqueios(@RequestParam(required = false) UUID usuarioId) {
        return ResponseEntity.ok(bloqueioAgendaService.listarBloqueios(getEmailLogado(), usuarioId));
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletarBloqueio(@PathVariable Long id) {
        bloqueioAgendaService.deletarBloqueio(id, getEmailLogado());
        return ResponseEntity.noContent().build();
    }
}