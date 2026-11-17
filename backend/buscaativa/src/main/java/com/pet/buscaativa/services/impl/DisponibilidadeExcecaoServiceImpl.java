package com.pet.buscaativa.services.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.DisponibilidadeExcecao;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.dto.DisponibilidadeExcecaoDTO;
import com.pet.buscaativa.repositories.DisponibilidadeExcecaoRepository;
import com.pet.buscaativa.services.DisponibilidadeExcecaoService;
import com.pet.buscaativa.services.exceptions.RecursoDuplicadoException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DisponibilidadeExcecaoServiceImpl implements DisponibilidadeExcecaoService {

    private final DisponibilidadeExcecaoRepository excecaoRepository;
    private final UsuarioContextService usuarioContextService;
    private final IntegridadeOfertaService integridade;

    @Override
    @org.springframework.transaction.annotation.Transactional
    public DisponibilidadeExcecaoDTO save(DisponibilidadeExcecaoDTO dto, String emailLogado) {
        if (dto == null) throw new com.pet.buscaativa.services.exceptions.ValidationException("Informe a exceção.");
        IntegridadeOfertaService.validarCamposExcecao(dto.data(), dto.turno(), dto.capacidade());
        var origem = integridade.origem(DisponibilidadeExcecao.class, dto.id());
        Usuario usuario = integridade.bloquear(dto.usuarioId(), origem, emailLogado);
        DisponibilidadeExcecao item = dto.id() == null ? new DisponibilidadeExcecao() : integridade.carregar(DisponibilidadeExcecao.class, dto.id());
        if (dto.id() != null) integridade.conferirOrigem(item, origem, emailLogado);
        var existente = excecaoRepository.findByUsuarioAndDataAndTurno(usuario, dto.data(), dto.turno());
        if (existente.isPresent() && !java.util.Objects.equals(existente.get().getId(), dto.id()))
            throw new RecursoDuplicadoException("Já existe uma configuração para esta data e turno. Edite a existente.");
        boolean mudou = dto.id() != null && (!java.util.Objects.equals(item.getUsuario().getId(), usuario.getId())
                || !item.getData().equals(dto.data()) || item.getTurno() != dto.turno());
        if (mudou) integridade.validarRetiradaExcecao(item.getUsuario(), item.getData(), item.getTurno());
        integridade.validarExcecao(usuario, dto.data(), dto.turno(), dto.capacidade());
        item.setUsuario(usuario);
        item.setData(dto.data());
        item.setTurno(dto.turno());
        item.setCapacidade(dto.capacidade());
        return toDTO(excecaoRepository.saveAndFlush(item));
    }

    @Override
    public List<DisponibilidadeExcecaoDTO> listar(String emailLogado, UUID usuarioIdPublico) {
        Usuario usuarioAlvo = usuarioContextService.determinarUsuarioAlvo(usuarioIdPublico, emailLogado);

        return excecaoRepository.findByUsuario(usuarioAlvo).stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void deletar(Long id, String emailLogado) {
        var origem = integridade.origem(DisponibilidadeExcecao.class, id);
        integridade.bloquear(origem.dono(), origem, emailLogado);
        DisponibilidadeExcecao item = integridade.carregar(DisponibilidadeExcecao.class, id);
        integridade.conferirOrigem(item, origem, emailLogado);
        integridade.validarRetiradaExcecao(item.getUsuario(), item.getData(), item.getTurno());
        excecaoRepository.delete(item);
        excecaoRepository.flush();
    }

    private DisponibilidadeExcecaoDTO toDTO(DisponibilidadeExcecao e) {
        return new DisponibilidadeExcecaoDTO(
                e.getId(),
                e.getUsuario().getIdPublico(),
                e.getData(),
                e.getTurno(),
                e.getCapacidade()
        );
    }
}