package com.pet.buscaativa.controllers;

import com.pet.buscaativa.entities.dto.UsuarioDTO;
import com.pet.buscaativa.entities.dto.ProfissionalSelecaoDTO;
import com.pet.buscaativa.entities.dto.UsuarioReferenciaDTO;
import com.pet.buscaativa.services.UsuarioService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;


import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/usuarios")
@RequiredArgsConstructor
public class UsuarioController {

    private final UsuarioService usuarioService;

    
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCAO')")
    public ResponseEntity<List<UsuarioDTO>> findAll() {
        return ResponseEntity.ok(usuarioService.findAll());
    }

    @GetMapping("/{idPublico}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCAO')")
    public ResponseEntity<UsuarioDTO> findById(@PathVariable UUID idPublico) {
        return ResponseEntity.ok(usuarioService.findById(idPublico));
    }

    @GetMapping("/profissionais-selecao")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCAO', 'PROFISSIONAL')")
    public ResponseEntity<List<ProfissionalSelecaoDTO>> listarProfissionaisParaSelecao() {
        return ResponseEntity.ok(usuarioService.listarProfissionaisParaSelecao());
    }

    @GetMapping("/referencia-selecao")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCAO', 'PROFISSIONAL')")
    public ResponseEntity<List<UsuarioReferenciaDTO>> listarUsuariosElegiveisParaReferencia() {
        return ResponseEntity.ok(usuarioService.listarUsuariosElegiveisParaReferencia());
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PostMapping
    public ResponseEntity<UsuarioDTO> insert(@Valid @RequestBody UsuarioDTO usuarioDTO) {
        UsuarioDTO novoUsuario = usuarioService.save(usuarioDTO);

        URI uri = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(novoUsuario.idPublico()).toUri();

        return ResponseEntity.created(uri).body(novoUsuario);
    }


    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @PutMapping("/{idPublico}")
    public ResponseEntity<UsuarioDTO> update(@PathVariable UUID idPublico, @Valid @RequestBody UsuarioDTO usuarioDTO) {
        UsuarioDTO usuarioAtualizar = new UsuarioDTO
                (idPublico, usuarioDTO.nome(), usuarioDTO.email(), usuarioDTO.tipoUsuario(), usuarioDTO.unidadeAtuacao(),
                        usuarioDTO.usfAtuacaoId(), null, usuarioDTO.senha());

        return ResponseEntity.ok(usuarioService.save(usuarioAtualizar));
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @DeleteMapping("/{idPublico}")
    public ResponseEntity<Void> delete(@PathVariable UUID idPublico) {
        usuarioService.removerUsuario(idPublico);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UsuarioDTO> me() {
        String emailLogado = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();

        UsuarioDTO usuario = usuarioService.findByEmail(emailLogado);

        if (usuario == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(usuario);
    }
}