package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.dto.AgendamentoDTO;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.services.HistoricoPacienteService;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Ensaio do POST de uma reserva nova. Não oferece rota nem escreve no cache global. */
@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class ReservaIndividualLocalEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final AgendamentoServiceImpl guardasAgenda;
    private final EvidenciaStatusVinculo evidencia;
    private final HistoricoPacienteService historico;
    private final EntityManager em;
    private final Clock clock;

    public record Resultado(Long atendimento, Integer versao) {}

    @Transactional
    public Resultado criar(AgendamentoDTO dto) {
        var contexto = atores.autorizarOperacional();
        if (contexto.perfil() != TipoUsuario.ADMINISTRADOR && contexto.perfil() != TipoUsuario.RECEPCAO)
            throw new AccessDeniedException("Perfil sem permissão para agendar.");
        if (dto == null || dto.id() != null || dto.agendamentoOriginalId() != null
                || dto.pacienteId() == null || dto.usuarioId() == null || dto.dataAgendamento() == null
                || dto.turnoAgendamento() == null || dto.horaAtendimento() == null)
            throw new ValidationException("Informe paciente, profissional e horário da reserva nova.");
        if (dto.dataAgendamento().isBefore(LocalDate.now(clock)))
            throw new ValidationException("Não é permitido criar agendamento em data passada.");
        UUID paciente = dto.pacienteId();
        UUID profissional = dto.usuarioId();
        var recursos = coordenacao.bloquear(() -> {
            var locais = coordenacao.identificar(List.of(paciente), contexto.unidade(), true);
            return CoordenacaoEscritaClinicaEnsaio.descoberta(locais, List.of(paciente), contexto, profissional,
                    List.of(), List.of(), List.of(), List.of(), locais);
        }, contexto, profissional);
        var local = recursos.vinculos().get(paciente);
        var responsavel = recursos.atores().referencia();
        if (!evidencia.analisar(local).comprovada())
            throw new ConflictException("Estado local do acompanhamento pendente de comprovação.");
        if (local.getStatusPaciente() != StatusPaciente.ATIVO)
            throw new ValidationException("Somente pacientes ativos podem ser agendados.");
        guardasAgenda.validarVagaOperacional(responsavel, local.getPaciente(), dto.dataAgendamento(),
                dto.turnoAgendamento(), dto.horaAtendimento(), null, local);
        var a = new Agendamento();
        a.setPaciente(local.getPaciente());
        a.setUsuario(responsavel);
        a.registrarUnidadeDeOrigem(responsavel);
        a.setDataAgendamento(dto.dataAgendamento());
        a.setHoraAtendimento(dto.horaAtendimento());
        a.setTurnoAgendamento(dto.turnoAgendamento());
        a.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        em.persist(a);
        historico.registrarAgendamento(a);
        em.flush();
        return new Resultado(a.getId(), a.getVersion());
    }
}
