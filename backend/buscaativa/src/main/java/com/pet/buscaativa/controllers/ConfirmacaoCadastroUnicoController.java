package com.pet.buscaativa.controllers;

import java.time.LocalDate;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pet.buscaativa.services.impl.ConfirmacaoVinculacaoIdentidadeService;

import lombok.RequiredArgsConstructor;

/**
 * Confirmacao EXPLICITA de uma identidade existente antes de criar o vinculo
 * assistencial da unidade autenticada. Nao participa do POST de novo paciente.
 *
 * Os documentos e os tres campos de confirmacao chegam no corpo HTTPS do
 * POST, nunca em URL/query string. A unidade vem exclusivamente da sessao
 * JWT. O servico transacional verifica documentos e dados demograficos duas
 * vezes antes de consolidar o vinculo.
 *
 * Sem retornar UUID global, links de outros servicos, status ou fatos
 * clinicos: estes ainda dependem de migracao coordenada dos filtros legados.
 * Nenhuma informacao enviada pelo cliente determina a unidade do vinculo.
 */
@RestController
@RequestMapping("/api/pacientes/cadastro-unico")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMINISTRADOR','RECEPCAO','PROFISSIONAL')")
public class ConfirmacaoCadastroUnicoController {
    private final ConfirmacaoVinculacaoIdentidadeService confirmacao;

    public record IdentidadeConfirmada(
            String cpf,
            String cns,
            String nome,
            String nomeMae,
            LocalDate dataNascimento) {}

    @PostMapping("/confirmar-vinculo")
    public ResponseEntity<Void> confirmarEVincular(@RequestBody IdentidadeConfirmada dados) {
        // Sem devolucao do ID global, do ID publico do vinculo ou de historico.
        // Sucesso significa apenas que o vinculo local existe apos o commit.
        confirmacao.confirmarEVincular(
                dados.cpf(), dados.cns(), dados.nome(), dados.nomeMae(),
                dados.dataNascimento());
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .build();
    }
}
