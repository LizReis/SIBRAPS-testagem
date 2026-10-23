package com.pet.buscaativa.controllers;

import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/unidades-saude")
@RequiredArgsConstructor
public class UsfReferenciaController {

    private final UsfReferenciaRepository usfRepository;

    @GetMapping
    public ResponseEntity<List<UsfReferencia>> listar() {

        return ResponseEntity.ok(
                usfRepository.findAllByOrderByNomeUsfAsc()
        );

    }
}
