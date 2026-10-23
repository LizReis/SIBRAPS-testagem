package com.pet.buscaativa.services.impl;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.Disponibilidade;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.dto.DisponibilidadeDTO;
import com.pet.buscaativa.repositories.DisponibilidadeRepository;
import com.pet.buscaativa.services.DisponibilidadeService;
import com.pet.buscaativa.services.exceptions.RecursoDuplicadoException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.services.exceptions.ConflictException;

import jakarta.transaction.Transactional;

@Service
@RequiredArgsConstructor
public class DisponibilidadeServiceImpl implements DisponibilidadeService {

    private final DisponibilidadeRepository disponibilidadeRepository;
    private final UsuarioContextService usuarioContextService;
    private final AgendamentoRepository agendamentoRepository;

    @Override
    public DisponibilidadeDTO save(DisponibilidadeDTO disponibilidadeDTO, String emailLogado) {
        Usuario usuario = usuarioContextService.determinarUsuarioAlvo(disponibilidadeDTO.usuarioId(), emailLogado);

        Optional<Disponibilidade> checarDisponibilidade = disponibilidadeRepository
                .findByUsuarioAndDiaDaSemanaAndTurno(usuario, disponibilidadeDTO.diaSemana(), disponibilidadeDTO.turno());

        if (checarDisponibilidade.isPresent()) {
            Disponibilidade existente = checarDisponibilidade.get();
            if (disponibilidadeDTO.id() == null || !existente.getId().equals(disponibilidadeDTO.id())) {
                throw new RecursoDuplicadoException("Já existe disponibilidade cadastrada para este profissional, dia da semana e turno.");
            }
        }

        Disponibilidade disponibilidade = new Disponibilidade();
        if (disponibilidadeDTO.id() != null) {
            disponibilidade = disponibilidadeRepository.findById(disponibilidadeDTO.id())
                    .orElseThrow(() -> new ResourceNotFoundException("Disponibilidade não encontrada."));
            usuarioContextService.validarAlteracao(disponibilidade.getUsuario(), emailLogado);

            // Se a capacidade está sendo reduzida, garante que ela ainda comporta
            // o dia futuro mais cheio já agendado para esse dia da semana + turno.
            int diaBanco = disponibilidadeDTO.diaSemana().getValue() % 7 + 1;

            List<Long> ocupacoesPorDia = agendamentoRepository.contarOcupacaoPorDataDisponibilidade(
                    usuario, LocalDate.now(), diaBanco, disponibilidadeDTO.turno(),
                    List.of(SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO, SituacaoAtendimento.PRESENTE));

            long maiorOcupacao = ocupacoesPorDia.stream().mapToLong(Long::longValue).max().orElse(0L);

            if (disponibilidadeDTO.capacidade() < maiorOcupacao) {
                throw new ConflictException(
                        "Não é possível reduzir a capacidade para " + disponibilidadeDTO.capacidade() +
                                ": já existem " + maiorOcupacao + " atendimento(s) marcados em um dos dias futuros para este horário.");
            }
        }

        disponibilidade.setUsuario(usuario);
        disponibilidade.setDiaDaSemana(disponibilidadeDTO.diaSemana());
        disponibilidade.setTurno(disponibilidadeDTO.turno());
        disponibilidade.setCapacidade(disponibilidadeDTO.capacidade());

        disponibilidade = disponibilidadeRepository.save(disponibilidade);

        return toDTO(disponibilidade);
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
        Disponibilidade disponibilidade = disponibilidadeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Disponibilidade não encontrada."));
        usuarioContextService.validarAlteracao(disponibilidade.getUsuario(), emailLogado);
        int diaBanco = disponibilidade.getDiaDaSemana().getValue() % 7 + 1;
        long conflitos = agendamentoRepository.contarConflitosDisponibilidade(disponibilidade.getUsuario(),
                LocalDate.now(), diaBanco, disponibilidade.getTurno(),
                List.of(SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO));
        if (conflitos > 0) {
            throw new ConflictException("Existem agendamentos ativos vinculados; cancele ou remarque antes da exclusão.");
        }
        disponibilidadeRepository.delete(disponibilidade);
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