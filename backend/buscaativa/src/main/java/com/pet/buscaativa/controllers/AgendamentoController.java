package com.pet.buscaativa.controllers;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.pet.buscaativa.entities.dto.AgendamentoDTO;
import com.pet.buscaativa.entities.dto.HorariosDisponiveisDTO;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TurnoEnum;
import com.pet.buscaativa.services.AgendamentoService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/agendamentos")
@RequiredArgsConstructor
public class AgendamentoController {

    private final AgendamentoService agendamentoService;

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCAO')")
    @PostMapping
    public ResponseEntity<AgendamentoDTO> criarAgendamento(@RequestBody @Valid AgendamentoDTO agendamentoDTO) {
        AgendamentoDTO agendamentoCriado = agendamentoService.save(agendamentoDTO);

        URI uri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(agendamentoCriado.id()).toUri();

        return ResponseEntity.created(uri).body(agendamentoCriado);
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @GetMapping("/{id}")
    public ResponseEntity<AgendamentoDTO> buscarPorId(@PathVariable Long id) {
        String emailLogado = SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.ok(agendamentoService.findById(id, emailLogado));
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCAO')")
    @GetMapping("/{id}/remarcacao")
    public ResponseEntity<List<LocalDate>> sugerirRemarcacao(@PathVariable Long id) {
        List<LocalDate> sugestoes = agendamentoService.sugerirDataRemarcacao(id);
        return ResponseEntity.ok(sugestoes);
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCAO', 'PROFISSIONAL')")
    @GetMapping("/vagas")
    public ResponseEntity<Map<TurnoEnum, Integer>> vagasDisponiveis(
            @RequestParam UUID usuarioId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data) {

        return ResponseEntity.ok(agendamentoService.calcularVagasDisponiveis(usuarioId, data));
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @GetMapping("/horarios-disponiveis")
    public ResponseEntity<HorariosDisponiveisDTO> horariosDisponiveis(@RequestParam UUID usuarioId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam TurnoEnum turno) {
        return ResponseEntity.ok(agendamentoService.buscarHorariosDisponiveis(usuarioId, data, turno));
    }


    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @GetMapping("/agenda")
    public ResponseEntity<List<AgendamentoDTO>> buscarAgendaPorPeriodo(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false) UUID profissionalId) {

        String emailLogado = SecurityContextHolder.getContext().getAuthentication().getName();

        return ResponseEntity.ok(agendamentoService.buscarAgendaPorPeriodo(dataInicio, dataFim, emailLogado, profissionalId));
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @GetMapping("/agenda/{data}")
    public ResponseEntity<List<AgendamentoDTO>> buscarAgendaDoDia(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) UUID profissionalId) {

        String emailLogado = SecurityContextHolder.getContext().getAuthentication().getName();

        return ResponseEntity.ok(agendamentoService.buscarAgendaDoDia(data, emailLogado, profissionalId));
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<AgendamentoDTO> atualizarStatus(
            @PathVariable Long id,
            @RequestParam SituacaoAtendimento novoStatus,
            @RequestParam(required = false) Integer version) {

        AgendamentoDTO agendamentoAtualizado = agendamentoService.atualizarStatus(id, novoStatus, version);
        return ResponseEntity.ok(agendamentoAtualizado);
    }
}