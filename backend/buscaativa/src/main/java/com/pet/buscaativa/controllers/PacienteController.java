package com.pet.buscaativa.controllers;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.pet.buscaativa.entities.dto.AlertaBuscaAtivaDTO;
import com.pet.buscaativa.entities.dto.EncerramentoPacienteDTO;
import com.pet.buscaativa.entities.dto.HistoricoPacienteDTO;
import com.pet.buscaativa.entities.dto.PacienteDTO;
import com.pet.buscaativa.entities.dto.ReativacaoPacienteDTO;
import com.pet.buscaativa.entities.dto.RegistroHistoricoPacienteDTO;
import com.pet.buscaativa.entities.dto.PacienteListaResponseDTO;
import com.pet.buscaativa.entities.dto.PacienteDetalheDTO;
import com.pet.buscaativa.entities.dto.AgendamentoDTO;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.HistoricoPacienteService;
import com.pet.buscaativa.services.PacienteService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/pacientes")
@RequiredArgsConstructor
public class PacienteController {

    private final PacienteService pacienteService;
    private final HistoricoPacienteService historicoPacienteService;

    @GetMapping
    public ResponseEntity<List<PacienteDTO>> listaPacientes(){
        return ResponseEntity.ok(pacienteService.findAll());
    }

    @GetMapping("/{idPublico}")
    public ResponseEntity<PacienteDTO> buscaPacienteId(@PathVariable UUID idPublico){
        return ResponseEntity.ok(pacienteService.findById(idPublico));
    }

    @GetMapping("/pesquisa")
    public ResponseEntity<PacienteListaResponseDTO> pesquisar(
            @RequestParam(required=false) String q,
            @RequestParam(required=false) ClassificacaoRisco classificacao,
            @RequestParam(defaultValue="ATIVO") StatusPaciente status,
            @RequestParam(required=false) TipoAcompanhamento tipoAcompanhamento,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="10") int size) {
        return ResponseEntity.ok(pacienteService.pesquisar(q, classificacao, status, tipoAcompanhamento, page, size));
    }

    @GetMapping("/{idPublico}/detalhe")
    public ResponseEntity<PacienteDetalheDTO> detalhe(@PathVariable UUID idPublico) {
        return ResponseEntity.ok(pacienteService.findDetalhe(idPublico));
    }

    @GetMapping("/{idPublico}/agendamentos")
    public ResponseEntity<List<AgendamentoDTO>> agendamentos(@PathVariable UUID idPublico) {
        return ResponseEntity.ok(pacienteService.listarAgendamentos(idPublico));
    }

    @GetMapping("/busca/cpf/{cpf}")
    public ResponseEntity<PacienteDTO> buscaPacienteCpf(@PathVariable String cpf){
        return ResponseEntity.ok(pacienteService.findByCpf(cpf));
    }

    @GetMapping("/busca/cns/{cns}")
    public ResponseEntity<PacienteDTO> buscaPacienteCns(@PathVariable String cns){
        return ResponseEntity.ok(pacienteService.findByCns(cns));
    }

    @GetMapping("/busca/nome")
    public ResponseEntity<List<PacienteDTO>> buscaPacienteNome(@RequestParam(value = "q") String nome){
        String termo = nome.trim();
        if (termo.length() < 3) {
            throw new com.pet.buscaativa.services.exceptions.ValidationException(
                    "Informe ao menos três caracteres para buscar por nome.");
        }
        return ResponseEntity.ok(pacienteService.findByNome(termo));
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCAO')")
    @PostMapping
    public ResponseEntity<PacienteDTO> insert(@Valid @RequestBody PacienteDTO pacienteDTO, 
        @RequestParam(value = "ignorarSimilaridade", defaultValue = "false") boolean ignorarSimilaridade){
        
            PacienteDTO novoPacienteDTO = pacienteService.save(pacienteDTO, ignorarSimilaridade);

            URI uri = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(novoPacienteDTO.idPublico()).toUri();

            return ResponseEntity.created(uri).body(novoPacienteDTO);
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCAO')")
    @PutMapping("/{idPublico}")
    public ResponseEntity<PacienteDTO> update(@PathVariable UUID idPublico, @Valid @RequestBody PacienteDTO pacienteDTO, 
        @RequestParam(value = "ignorarSimilaridade", defaultValue = "false") boolean ignorarSimilaridade){

            PacienteDTO pacienteAtualizar = new PacienteDTO(idPublico, pacienteDTO.nome(), pacienteDTO.nomeMae(), pacienteDTO.dataNascimento(), pacienteDTO.dataUltimaPresenca(),
                pacienteDTO.sexo(), pacienteDTO.racacor(), pacienteDTO.cns(), pacienteDTO.cpf(), pacienteDTO.telefone(), pacienteDTO.endereco(),
                pacienteDTO.situacaoRua(), pacienteDTO.tipoAcompanhamento(), pacienteDTO.countFaltas(), pacienteDTO.statusPaciente(),
                pacienteDTO.usfReferencia(), pacienteDTO.capsReferencia(), pacienteDTO.profissionalReferenciaId(), null);

                return ResponseEntity.ok(pacienteService.save(pacienteAtualizar, ignorarSimilaridade));
        }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @DeleteMapping("/{idPublico}")
    public ResponseEntity<Void> inativarPaciente(@PathVariable UUID idPublico){

        pacienteService.inativarPaciente(idPublico);
        return ResponseEntity.noContent().build();

    }


    @GetMapping("/busca-ativa")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    public ResponseEntity<List<AlertaBuscaAtivaDTO>> listarBuscaAtiva(){
        return ResponseEntity.ok(pacienteService.listarPacientesEmBuscaAtiva());
    }


    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL')")
    @PatchMapping("/{idPublico}/encerrar")
    public ResponseEntity<Void> encerrarAcompanhamento(@PathVariable UUID idPublico, @Valid @RequestBody EncerramentoPacienteDTO encerramento) {
        
        pacienteService.encerrarAcompanhamento(idPublico, encerramento);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PatchMapping("/{idPublico}/reativar")
    public ResponseEntity<Void> reativarAcompanhamento(@PathVariable UUID idPublico, @Valid @RequestBody ReativacaoPacienteDTO reativacao) {
        pacienteService.reativarAcompanhamento(idPublico, reativacao);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
    @GetMapping("/{idPublico}/historico")
    public ResponseEntity<HistoricoPacienteDTO> consultarHistorico(@PathVariable UUID idPublico) {
        return ResponseEntity.ok(historicoPacienteService.consultar(idPublico));
    }

    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL')")
    @PostMapping("/{idPublico}/historico")
    public ResponseEntity<Void> registrarNoHistorico(@PathVariable UUID idPublico,
            @Valid @RequestBody RegistroHistoricoPacienteDTO registro) {
        historicoPacienteService.registrarManual(idPublico, registro);
        return ResponseEntity.noContent().build();
    }
}
