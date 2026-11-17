package com.pet.buscaativa.services.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.BloqueioAgenda;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.dto.BloqueioAgendaDTO;
import com.pet.buscaativa.repositories.BloqueioAgendaRepository;
import com.pet.buscaativa.services.BloqueioAgendaService;
import com.pet.buscaativa.services.exceptions.ValidationException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BloqueioAgendaServiceImpl implements BloqueioAgendaService {

    private final BloqueioAgendaRepository bloqueioRepository;
    private final UsuarioContextService usuarioContextService;
    private final IntegridadeOfertaService integridade;

    @Override
    @org.springframework.transaction.annotation.Transactional
    public BloqueioAgendaDTO save(BloqueioAgendaDTO dto, String emailLogado) {
        if (dto == null || dto.dataInicio() == null || dto.dataFim() == null
                || dto.motivoBloqueio() == null || dto.motivoBloqueio().isBlank())
            throw new ValidationException("Informe o período e o motivo do bloqueio.");
        if (dto.dataFim().isBefore(dto.dataInicio()))
            throw new ValidationException("A data final não pode ser anterior à data inicial.");
        var origem = integridade.origem(BloqueioAgenda.class, dto.id());
        Usuario usuario = integridade.bloquear(dto.usuarioId(), origem, emailLogado);
        BloqueioAgenda item = dto.id() == null ? new BloqueioAgenda() : integridade.carregar(BloqueioAgenda.class, dto.id());
        if (dto.id() != null) integridade.conferirOrigem(item, origem, emailLogado);
        integridade.validarBloqueio(usuario, dto.dataInicio(), dto.dataFim());
        item.setUsuario(usuario);
        item.setDataInicio(dto.dataInicio());
        item.setDataFim(dto.dataFim());
        item.setMotivoBloqueio(dto.motivoBloqueio());
        return toDTO(bloqueioRepository.saveAndFlush(item));
    }

    @Override
    public List<BloqueioAgendaDTO> listarBloqueios(String emailLogado, UUID usuarioIdPublico) {
        Usuario usuarioAlvo = usuarioContextService.determinarUsuarioAlvo(usuarioIdPublico, emailLogado);

        return bloqueioRepository.findByUsuario(usuarioAlvo).stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void deletarBloqueio(Long id, String emailLogado) {
        var origem = integridade.origem(BloqueioAgenda.class, id);
        integridade.bloquear(origem.dono(), origem, emailLogado);
        BloqueioAgenda item = integridade.carregar(BloqueioAgenda.class, id);
        integridade.conferirOrigem(item, origem, emailLogado);
        bloqueioRepository.delete(item);
        bloqueioRepository.flush();
    }

    private BloqueioAgendaDTO toDTO(BloqueioAgenda b) {
        return new BloqueioAgendaDTO(
                b.getId(),
                b.getUsuario().getIdPublico(),
                b.getDataInicio(),
                b.getDataFim(),
                b.getMotivoBloqueio()
        );
    }
}