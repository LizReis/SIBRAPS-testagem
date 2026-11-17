package com.pet.buscaativa.services.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.Disponibilidade;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.dto.DisponibilidadeDTO;
import com.pet.buscaativa.repositories.DisponibilidadeRepository;
import com.pet.buscaativa.services.DisponibilidadeService;
import com.pet.buscaativa.services.exceptions.RecursoDuplicadoException;

import lombok.RequiredArgsConstructor;

import java.util.UUID;


import jakarta.transaction.Transactional;

@Service
@RequiredArgsConstructor
public class DisponibilidadeServiceImpl implements DisponibilidadeService {

    private final DisponibilidadeRepository disponibilidadeRepository;
    private final UsuarioContextService usuarioContextService;
    private final IntegridadeOfertaService integridade;

    @Override
    @org.springframework.transaction.annotation.Transactional
    public DisponibilidadeDTO save(DisponibilidadeDTO dto, String emailLogado) {
        if (dto == null) throw new com.pet.buscaativa.services.exceptions.ValidationException("Informe a disponibilidade.");
        IntegridadeOfertaService.validarCamposSemanais(dto.diaSemana(), dto.turno(), dto.capacidade());
        var origem = integridade.origem(Disponibilidade.class, dto.id());
        Usuario usuario = integridade.bloquear(dto.usuarioId(), origem, emailLogado);
        Disponibilidade item = dto.id() == null ? new Disponibilidade() : integridade.carregar(Disponibilidade.class, dto.id());
        if (dto.id() != null) integridade.conferirOrigem(item, origem, emailLogado);
        var existente = disponibilidadeRepository.findByUsuarioAndDiaDaSemanaAndTurno(usuario, dto.diaSemana(), dto.turno());
        if (existente.isPresent() && !java.util.Objects.equals(existente.get().getId(), dto.id()))
            throw new RecursoDuplicadoException("Já existe disponibilidade cadastrada para este profissional, dia da semana e turno.");
        boolean mudou = dto.id() != null && (!java.util.Objects.equals(item.getUsuario().getId(), usuario.getId())
                || item.getDiaDaSemana() != dto.diaSemana() || item.getTurno() != dto.turno());
        if (mudou) integridade.validarSemanal(item.getUsuario(), item.getDiaDaSemana(), item.getTurno(), 0);
        integridade.validarSemanal(usuario, dto.diaSemana(), dto.turno(), dto.capacidade());
        item.setUsuario(usuario);
        item.setDiaDaSemana(dto.diaSemana());
        item.setTurno(dto.turno());
        item.setCapacidade(dto.capacidade());
        return toDTO(disponibilidadeRepository.saveAndFlush(item));
    }

    @Override
    public List<DisponibilidadeDTO> listarDisponibilidades(String emailLogado, UUID usuarioIdPublico) {
        Usuario usuarioAlvo = usuarioContextService.determinarUsuarioAlvo(usuarioIdPublico, emailLogado);

        return disponibilidadeRepository.findByUsuario(usuarioAlvo).stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    @Transactional
    public void deletarDisponibilidade(Long id, String emailLogado) {
        var origem = integridade.origem(Disponibilidade.class, id);
        integridade.bloquear(origem.dono(), origem, emailLogado);
        Disponibilidade item = integridade.carregar(Disponibilidade.class, id);
        integridade.conferirOrigem(item, origem, emailLogado);
        integridade.validarSemanal(item.getUsuario(), item.getDiaDaSemana(), item.getTurno(), 0);
        disponibilidadeRepository.delete(item);
        disponibilidadeRepository.flush();
    }

    private DisponibilidadeDTO toDTO(Disponibilidade d) {
        return new DisponibilidadeDTO(
                d.getId(),
                d.getUsuario().getIdPublico(),
                d.getDiaDaSemana(),
                d.getTurno(),
                d.getCapacidade()
        );
    }
}