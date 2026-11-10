package com.pet.buscaativa.controllers;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import com.pet.buscaativa.entities.dto.AuditoriaCorrecaoDTO;
import com.pet.buscaativa.services.AuditoriaCorrecaoService;
import lombok.RequiredArgsConstructor;

@RestController @RequestMapping("/api") @RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMINISTRADOR','PROFISSIONAL')")
public class AuditoriaCorrecaoController {
    private final AuditoriaCorrecaoService service;
    @GetMapping("/agendamentos/{id}/correcoes")
    public Page<AuditoriaCorrecaoDTO> individual(@PathVariable Long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.listarAgendamento(id,page,size);}
    @GetMapping("/grupos/sessoes/{id}/correcoes")
    public Page<AuditoriaCorrecaoDTO> grupo(@PathVariable Long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.listarSessao(id,page,size);}
}
