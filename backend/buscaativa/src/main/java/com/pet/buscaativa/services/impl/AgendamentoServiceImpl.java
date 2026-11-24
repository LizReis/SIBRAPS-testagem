package com.pet.buscaativa.services.impl;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.BloqueioAgenda;
import com.pet.buscaativa.entities.Disponibilidade;
import com.pet.buscaativa.entities.DisponibilidadeExcecao;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.dto.AgendamentoDTO;
import com.pet.buscaativa.entities.dto.HorariosDisponiveisDTO;
import com.pet.buscaativa.entities.dto.HorariosDisponiveisDTO.HorarioDisponivelDTO;
import com.pet.buscaativa.config.AgendaHorarioProperties;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.TurnoEnum;
import com.pet.buscaativa.mapping.AgendamentoMapper;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.BloqueioAgendaRepository;
import com.pet.buscaativa.repositories.DisponibilidadeExcecaoRepository;
import com.pet.buscaativa.repositories.DisponibilidadeRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.AgendamentoService;
import com.pet.buscaativa.services.HistoricoPacienteService;
import com.pet.buscaativa.services.PacienteService;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AgendamentoServiceImpl implements AgendamentoService {

    private static final long LIMITE_DIAS_CONSULTA_AGENDA = 62;

    private final AgendamentoRepository agendamentoRepository;
    private final AgendaAssistencialLocks agendaAssistencialLocks;
    private final IntegridadeAgendaAssistencialService integridadeAgendaAssistencial;
    private final com.pet.buscaativa.repositories.SessaoGrupoRepository sessaoGrupoRepository;
    private final BloqueioAgendaRepository bloqueioAgendaRepository;
    private final UsuarioRepository usuarioRepository;
    private final DisponibilidadeRepository disponibilidadeRepository;
    private final PacienteRepository pacienteRepository;
    private final DisponibilidadeExcecaoRepository disponibilidadeExcecaoRepository;

    private final AgendamentoMapper agendamentoMapper;

    private final PacienteService pacienteService;
    private final HistoricoPacienteService historicoPacienteService;
    private final AgendaHorarioProperties agendaHorarioProperties;
    private final java.time.Clock clock;
    private final jakarta.persistence.EntityManager entityManager;
    private final com.pet.buscaativa.services.PoliticaTemporalFrequencia politicaTemporal;
    private final EscopoUnidadeService escopoUnidadeService;
    private final com.pet.buscaativa.services.AutorizacaoClinicaService autorizacaoClinica;
    private final com.pet.buscaativa.services.PoliticaJustificativaCorrecao politicaJustificativa;
    private final com.pet.buscaativa.services.AuditoriaCorrecaoService auditoriaCorrecao;
    private final com.pet.buscaativa.services.AuditoriaRegularizacaoService auditoriaRegularizacao;
    private static final List<SituacaoAtendimento> SITUACOES_ATIVAS = List.of(
            SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO, SituacaoAtendimento.PRESENTE);

    @Override
    @Transactional
    public AgendamentoDTO save(AgendamentoDTO agendamentoDTO) {
        if (agendamentoDTO == null || agendamentoDTO.pacienteId() == null || agendamentoDTO.usuarioId() == null
                || agendamentoDTO.dataAgendamento() == null) {
            throw new ValidationException("Informe paciente, profissional e data do atendimento.");
        }
        validarHorario(agendamentoDTO.turnoAgendamento(), agendamentoDTO.horaAtendimento());
        // Descoberta escalar antes de qualquer lock: remarcação também afeta a oferta de origem.
        Long originalId = agendamentoDTO.agendamentoOriginalId();
        var identidadeOriginal = originalId == null ? null : agendamentoRepository.findLockIdentityById(originalId)
                .orElseThrow(() -> new ResourceNotFoundException("Agendamento original não encontrado"));
        var pacientesIds = new HashSet<UUID>();
        var profissionaisIds = new HashSet<UUID>();
        pacientesIds.add(agendamentoDTO.pacienteId());
        profissionaisIds.add(agendamentoDTO.usuarioId());
        if (identidadeOriginal != null) {
            pacientesIds.add(identidadeOriginal.getPacienteId());
            profissionaisIds.add(identidadeOriginal.getUsuarioId());
        }
        var recursos = agendaAssistencialLocks.bloquear(pacientesIds, profissionaisIds);
        Paciente paciente = recursos.paciente(agendamentoDTO.pacienteId());
        Usuario usuario = recursos.profissional(agendamentoDTO.usuarioId());
        Agendamento original = identidadeOriginal == null ? null : carregarAgendamentoBloqueado(originalId, identidadeOriginal);
        if (original != null && !java.util.Objects.equals(original.getPaciente().getId(), paciente.getId())) {
            throw new ValidationException("A remarcação deve pertencer ao mesmo paciente do atendimento original.");
        }
        validarPacienteElegivelParaReserva(paciente);
        validarProfissionalElegivelParaReserva(usuario);

        if (agendamentoDTO.dataAgendamento().isBefore(LocalDate.now(clock))) {
            throw new ValidationException("Não é permitido criar agendamento em data passada.");
        }

        validarVagaOperacional(usuario, paciente, agendamentoDTO.dataAgendamento(),
                agendamentoDTO.turnoAgendamento(), agendamentoDTO.horaAtendimento(), null);

        Agendamento agendamento = new Agendamento();
        agendamento.setUsuario(usuario);
        agendamento.setPaciente(paciente);
        // Proveniencia do novo registro: deriva somente do profissional
        // autorizado e bloqueado nesta criacao, nunca do CAPS/USF de referencia.
        agendamento.registrarUnidadeDeOrigem(usuario);
        agendamento.setDataAgendamento(agendamentoDTO.dataAgendamento());
        agendamento.setTurnoAgendamento(agendamentoDTO.turnoAgendamento());
        agendamento.setHoraAtendimento(agendamentoDTO.horaAtendimento());

        if (original != null) {
            if (original.isMigracaoPendente())
                throw new ConflictException("Atendimento original com informações históricas pendentes de revisão; nenhuma remarcação foi realizada.");
            if (agendamentoDTO.id() != null && agendamentoDTO.id().equals(agendamentoDTO.agendamentoOriginalId())) {
                throw new ValidationException("Um agendamento não pode ser vinculado a ele mesmo.");
            }
            validarOrigemDaRemarcacao(original, agendamentoDTO.dataAgendamento(), agendamentoDTO.horaAtendimento());

            if (paciente.getTipoAcompanhamento() == TipoAcompanhamento.GRUPO_TERAPEUTICO) {
                throw new ValidationException(
                        "Remarcação individual não permitida para atendimentos de Grupo Terapêutico.");
            }

            SituacaoAtendimento statusAnterior = original.getSituacaoAtendimento();
            agendamento.setRemarcacaoAposFalta(statusAnterior == SituacaoAtendimento.FALTOU);
            original.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
            agendamentoRepository.save(original);
            historicoPacienteService.registrarAlteracaoDeAtendimento(original, statusAnterior);
            agendamento.setAgendamentoOriginal(original);
            agendamento.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO);
        } else {
            agendamento.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        }

        agendamento = agendamentoRepository.save(agendamento);
        historicoPacienteService.registrarAgendamento(agendamento);
        if (agendamentoDTO.agendamentoOriginalId() != null) pacienteService.recalcularAssiduidadePaciente(paciente);
        return agendamentoMapper.toAgendamentoDTO(agendamento);
    }

    /**
     * A criação de sucessor é o único caminho operacional de remarcação.
     * Verifica a cadeia existente, sem atribuir legitimidade a fatos históricos
     * ambíguos e antes de modificar a reserva original.
     *
     * O lock do atendimento original já foi adquirido no protocolo global.
     * A integração concorrente completa de registros legados é revisitada em 6.2F.
     */
    private void validarOrigemDaRemarcacao(Agendamento original, LocalDate novaData, LocalTime novaHora) {
        var reserva = original.getSituacaoReserva();
        if (reserva != com.pet.buscaativa.entities.enums.SituacaoReserva.AGENDADA
                && reserva != com.pet.buscaativa.entities.enums.SituacaoReserva.REMARCADA)
            throw new ConflictException("A reserva original não permite criar sucessor por remarcação.");

        if (!List.of(SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO,
                SituacaoAtendimento.FALTOU).contains(original.getSituacaoAtendimento()))
            throw new ConflictException("O agendamento original não está em situação compatível com remarcação.");

        // Não há segundo sucessor legítimo para a mesma origem. O eventual estado
        // legado AGENDADO da origem não pode mascarar um sucessor já existente.
        if (agendamentoRepository.existsByAgendamentoOriginal_Id(original.getId()))
            throw cadeiaPendente(original);

        if (original.getDataAgendamento() == null || original.getHoraAtendimento() == null
                || !java.time.LocalDateTime.of(original.getDataAgendamento(), original.getHoraAtendimento())
                        .isBefore(java.time.LocalDateTime.of(novaData, novaHora)))
            throw new ConflictException("A remarcação deve ocorrer após o atendimento original.");

        var visitados = new HashSet<Long>();
        Agendamento atual = original;
        while (atual != null) {
            if (!visitados.add(atual.getId()) || atual.isMigracaoPendente())
                throw cadeiaPendente(original);
            var pai = atual.getAgendamentoOriginal();
            if (pai == null) {
                if (atual.getSituacaoReserva() == com.pet.buscaativa.entities.enums.SituacaoReserva.REMARCADA
                        || atual.isRemarcacaoAposFalta())
                    throw cadeiaPendente(original);
                break;
            }
            escopoUnidadeService.validarAgendamento(pai);
            if (!java.util.Objects.equals(atual.getPaciente().getId(), pai.getPaciente().getId())
                    || pai.getSituacaoReserva() != com.pet.buscaativa.entities.enums.SituacaoReserva.SUBSTITUIDA
                    || pai.getDataAgendamento() == null || pai.getHoraAtendimento() == null
                    || atual.getDataAgendamento() == null || atual.getHoraAtendimento() == null
                    || !java.time.LocalDateTime.of(pai.getDataAgendamento(), pai.getHoraAtendimento())
                            .isBefore(java.time.LocalDateTime.of(atual.getDataAgendamento(), atual.getHoraAtendimento()))
                    || agendamentoRepository.existsByAgendamentoOriginal_IdAndIdNot(pai.getId(), atual.getId()))
                throw cadeiaPendente(original);
            atual = pai;
        }
        if ((reserva == com.pet.buscaativa.entities.enums.SituacaoReserva.AGENDADA
                    && original.getAgendamentoOriginal() != null)
                || (reserva == com.pet.buscaativa.entities.enums.SituacaoReserva.REMARCADA
                    && original.getAgendamentoOriginal() == null))
            throw cadeiaPendente(original);
    }

    private void validarPacienteElegivelParaReserva(Paciente paciente) {
        if (paciente.getStatusPaciente() != StatusPaciente.ATIVO) {
            throw new ValidationException("Somente pacientes ativos podem ser agendados.");
        }
    }

    private void validarProfissionalElegivelParaReserva(Usuario usuario) {
        if (usuario.getTipoUsuario() != TipoUsuario.PROFISSIONAL
                && usuario.getTipoUsuario() != TipoUsuario.ADMINISTRADOR) {
            throw new ValidationException("O usuário selecionado não é um profissional habilitado para atendimento.");
        }
    }

    private void validarVaga(Usuario usuario, Paciente paciente, LocalDate data, TurnoEnum turno, LocalTime hora) {
        validarVaga(usuario,paciente,data,turno,hora,null);
    }
    private void validarVaga(Usuario usuario,Paciente paciente,LocalDate data,TurnoEnum turno,LocalTime hora,Long recuperacao) {
        validarHorario(turno, hora);
        validarConflitosDeHorario(usuario, paciente, data, hora, recuperacao);
        validarOferta(usuario, data, turno);
    }

    /** Operações de reserva; o caminho factual legado fica intacto até 6.2D. */
    private void validarVagaOperacional(Usuario usuario, Paciente paciente, LocalDate data, TurnoEnum turno,
            LocalTime hora, Long recuperacao) {
        validarVagaOperacional(usuario, paciente, data, turno, hora, recuperacao, null);
    }

    void validarVagaOperacional(Usuario usuario, Paciente paciente, LocalDate data, TurnoEnum turno,
            LocalTime hora, Long recuperacao, VinculoAssistencial local) {
        validarHorario(turno, hora);
        var instante = new IntegridadeAgendaAssistencialService.Instante(data, hora);
        var exclusao = new IntegridadeAgendaAssistencialService.Exclusao(recuperacao, null);
        var profissional = integridadeAgendaAssistencial.avaliarProfissional(usuario, instante, exclusao);
        var pessoa = local == null ? integridadeAgendaAssistencial.avaliarPaciente(paciente, instante, exclusao)
                : integridadeAgendaAssistencial.avaliarPaciente(local, instante, exclusao);
        validarSinaisDeReserva(profissional, pessoa, recuperacao);
        validarOferta(usuario, data, turno);
    }

    private void validarSinaisDeReserva(IntegridadeAgendaAssistencialService.Avaliacao profissional,
            IntegridadeAgendaAssistencialService.Avaliacao pessoa, Long recuperacao) {
        var ocupacao = IntegridadeAgendaAssistencialService.Sinal.OCUPACAO_INDIVIDUAL;
        var presenca = IntegridadeAgendaAssistencialService.Sinal.PRESENCA_INDIVIDUAL;
        if (profissional.tem(ocupacao)) recusarReserva(recuperacao, "PROFISSIONAL",
                "Já existe um atendimento marcado para este profissional nesta data e horário.");
        if (pessoa.tem(ocupacao)) recusarReserva(recuperacao, "PACIENTE",
                "O paciente já possui um atendimento ativo nesta data e horário.");
        if (profissional.tem(presenca)) recusarReserva(recuperacao, "HISTORICO_PROFISSIONAL",
                "Existe fato assistencial incompatível neste horário; revisão autorizada necessária.");
        if (pessoa.tem(presenca)) recusarReserva(recuperacao, "HISTORICO_PACIENTE",
                "Existe fato assistencial incompatível neste horário; revisão autorizada necessária.");
        if (profissional.tem(IntegridadeAgendaAssistencialService.Sinal.SESSAO_AGENDADA)
                || profissional.tem(IntegridadeAgendaAssistencialService.Sinal.SESSAO_REALIZADA))
            recusarReserva(recuperacao, "SESSAO_PROFISSIONAL", "O profissional possui compromisso assistencial incompatível neste horário.");
        // Recuperação mantém a guarda conservadora anterior para participação realizada com falta ou resultado desconhecido.
        if (pessoa.tem(IntegridadeAgendaAssistencialService.Sinal.INSCRICAO_AGENDADA)
                || pessoa.tem(IntegridadeAgendaAssistencialService.Sinal.PRESENCA_GRUPO)
                || (recuperacao != null && (pessoa.tem(IntegridadeAgendaAssistencialService.Sinal.FALTA_GRUPO)
                    || pessoa.tem(IntegridadeAgendaAssistencialService.Sinal.FREQUENCIA_GRUPO_DESCONHECIDA))))
            recusarReserva(recuperacao, "PARTICIPACAO_GRUPO", "O paciente possui compromisso assistencial incompatível neste horário.");
    }

    private void recusarReserva(Long recuperacao, String tipo, String mensagem) {
        if (recuperacao != null) throw conflitoAssistencial(recuperacao,tipo);
        throw new ConflictException(mensagem);
    }

    private void validarOferta(Usuario usuario, LocalDate data, TurnoEnum turno) {
        if (bloqueioAgendaRepository.isDataBloqueadaParaUsuario(usuario, data)) {
            throw new ConflictException("A agenda do profissional está bloqueada na data informada.");
        }


        // Resolve a capacidade considerando exceção de data específica (prioridade)
        // e, na ausência dela, o padrão semanal.
        Integer capacidade = resolverCapacidade(usuario, data,
                data.getDayOfWeek(), turno);

        if (capacidade == null || capacidade <= 0) {
            throw new ConflictException("Não existe disponibilidade para a data e turno informados.");
        }

        int ocupadas = agendamentoRepository.contarVagasOcupadasBySituacoes(usuario, data,
                turno, List.of(SituacaoAtendimento.AGENDADO,
                        SituacaoAtendimento.REMARCADO, SituacaoAtendimento.PRESENTE));
        if (ocupadas >= capacidade) {
            throw new ConflictException("Não há vagas disponíveis para a data e turno informados.");
        }

    }

    private void validarPresencaIndividual(Agendamento atendimento) {
        validarPresencaIndividual(atendimento, null);
    }

    void validarPresencaIndividual(Agendamento atendimento, VinculoAssistencial local) {
        var instante = new IntegridadeAgendaAssistencialService.Instante(
                atendimento.getDataAgendamento(), atendimento.getHoraAtendimento());
        var exclusao = new IntegridadeAgendaAssistencialService.Exclusao(atendimento.getId(), null);
        var profissional = integridadeAgendaAssistencial.avaliarProfissional(atendimento.getUsuario(), instante, exclusao);
        var paciente = local == null ? integridadeAgendaAssistencial.avaliarPaciente(atendimento.getPaciente(), instante, exclusao)
                : integridadeAgendaAssistencial.avaliarPaciente(local, instante, exclusao);
        if (profissional.tem(IntegridadeAgendaAssistencialService.Sinal.OCUPACAO_INDIVIDUAL))
            throw conflitoAssistencial(atendimento.getId(), "PROFISSIONAL");
        if (paciente.tem(IntegridadeAgendaAssistencialService.Sinal.OCUPACAO_INDIVIDUAL))
            throw conflitoAssistencial(atendimento.getId(), "PACIENTE");
        if (profissional.tem(IntegridadeAgendaAssistencialService.Sinal.PRESENCA_INDIVIDUAL))
            throw conflitoAssistencial(atendimento.getId(), "HISTORICO_PROFISSIONAL");
        if (paciente.tem(IntegridadeAgendaAssistencialService.Sinal.PRESENCA_INDIVIDUAL))
            throw conflitoAssistencial(atendimento.getId(), "HISTORICO_PACIENTE");
        if (profissional.tem(IntegridadeAgendaAssistencialService.Sinal.SESSAO_AGENDADA)
                || profissional.tem(IntegridadeAgendaAssistencialService.Sinal.SESSAO_REALIZADA))
            throw conflitoAssistencial(atendimento.getId(), "SESSAO_PROFISSIONAL");
        if (paciente.tem(IntegridadeAgendaAssistencialService.Sinal.INSCRICAO_AGENDADA)
                || paciente.tem(IntegridadeAgendaAssistencialService.Sinal.PRESENCA_GRUPO))
            throw conflitoAssistencial(atendimento.getId(), "PARTICIPACAO_GRUPO");
        // Falta em outra modalidade não prova presença. As guardas legadas mais
        // conservadoras de correção e regularização permanecem nos chamadores.
    }

    void validarConflitosDeHorario(Usuario usuario, Paciente paciente, LocalDate data, LocalTime hora,
            Long atendimentoEmCorrecao) {
        boolean correcaoFrequencia = atendimentoEmCorrecao != null;
        boolean horarioJaOcupado = agendamentoRepository.existsAgendamentoAtivoNoMesmoHorario(
                usuario, data, hora,
                SITUACOES_ATIVAS);

        if (horarioJaOcupado) {
            if (correcaoFrequencia) throw conflitoAssistencial(atendimentoEmCorrecao,"PROFISSIONAL");
            throw new ConflictException(
                    "Já existe um atendimento marcado para este profissional nesta data e horário.");
        }

        if (agendamentoRepository.existsAtivoDoPacienteNoMesmoHorario(paciente,
                data, hora, SITUACOES_ATIVAS)) {
            if (correcaoFrequencia) throw conflitoAssistencial(atendimentoEmCorrecao,"PACIENTE");
            throw new ConflictException("O paciente já possui um atendimento ativo nesta data e horário.");
        }
        if (agendamentoRepository.existsPresencaDoProfissional(usuario,data,hora,atendimentoEmCorrecao)) {
            if(correcaoFrequencia)throw conflitoAssistencial(atendimentoEmCorrecao,"HISTORICO_PROFISSIONAL");
            throw new ConflictException("Existe fato assistencial incompatível neste horário; revisão autorizada necessária.");
        }
        if (agendamentoRepository.existsPresencaDoPaciente(paciente,data,hora,atendimentoEmCorrecao)) {
            if(correcaoFrequencia)throw conflitoAssistencial(atendimentoEmCorrecao,"HISTORICO_PACIENTE");
            throw new ConflictException("Existe fato assistencial incompatível neste horário; revisão autorizada necessária.");
        }
        if (correcaoFrequencia && sessaoGrupoRepository.existsSessaoDoResponsavelNoMesmoHorario(usuario,data,hora))
            throw conflitoAssistencial(atendimentoEmCorrecao,"SESSAO_PROFISSIONAL");
        // Reuse the existing group participation conflict rule; no group row or third party is changed.
        if (correcaoFrequencia && sessaoGrupoRepository.existsParticipacaoNoMesmoHorario(paciente,data,hora,
                com.pet.buscaativa.entities.enums.StatusSessaoGrupo.CANCELADA))
            throw conflitoAssistencial(atendimentoEmCorrecao,"PARTICIPACAO_GRUPO");
    }

    private com.pet.buscaativa.services.exceptions.PendenciaFrequenciaException conflitoAssistencial(Long id,String tipo) {
        return new com.pet.buscaativa.services.exceptions.PendenciaFrequenciaException(
            "CONFLITO_ASSISTENCIAL_PENDENTE_RECONCILIACAO",tipo,id,null);
    }

    private void validarHorario(TurnoEnum turno, LocalTime hora) {
        if (turno == null || hora == null) {
            throw new ValidationException("Informe o turno e o horário do atendimento.");
        }
        if (!agendaHorarioProperties.grade(turno).contains(hora)) {
            throw new ValidationException("O horário deve pertencer à grade configurada para o turno informado.");
        }
    }

    @Override
    public List<LocalDate> sugerirDataRemarcacao(Long agendamento) {
        var original = agendamentoRepository.findById(agendamento)
                .orElseThrow(() -> new ResourceNotFoundException("Agendamento não encontrado"));

        escopoUnidadeService.validarAgendamento(original);
        if (original.getPaciente() != null
                && original.getPaciente().getTipoAcompanhamento() == TipoAcompanhamento.GRUPO_TERAPEUTICO) {
            throw new ValidationException(
                    "Não é possível sugerir remarcação individual para atendimentos de Grupo Terapêutico.");
        }

        return buscarProximasVagasDisponiveis(original.getUsuario(), original.getTurnoAgendamento(), LocalDate.now(clock),
                3);
    }

    @Override
    public Map<TurnoEnum, Integer> calcularVagasDisponiveis(UUID usuarioIdPublico, LocalDate data) {
        Usuario usuario = usuarioRepository.findByIdPublico(usuarioIdPublico)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado: " + usuarioIdPublico));

        escopoUnidadeService.validarUsuario(usuario);
        Map<TurnoEnum, Integer> vagasPorTurno = new HashMap<>();
        vagasPorTurno.put(TurnoEnum.MANHA, 0);
        vagasPorTurno.put(TurnoEnum.TARDE, 0);

        boolean isBloqueado = bloqueioAgendaRepository.isDataBloqueadaParaUsuario(usuario, data);
        if (isBloqueado) {
            return vagasPorTurno;
        }

        DayOfWeek diaSemana = data.getDayOfWeek();

        List<SituacaoAtendimento> ocupantesVaga = List.of(
                SituacaoAtendimento.AGENDADO,
                SituacaoAtendimento.REMARCADO,
                SituacaoAtendimento.PRESENTE);

        for (TurnoEnum turno : List.of(TurnoEnum.MANHA, TurnoEnum.TARDE)) {

            Integer capacidade = resolverCapacidade(usuario, data, diaSemana, turno);

            if (capacidade == null) {
                // Sem exceção e sem padrão semanal cadastrado para esse turno -> indisponível
                continue;
            }

            int ocupadas = agendamentoRepository.contarVagasOcupadasBySituacoes(usuario, data, turno, ocupantesVaga);
            vagasPorTurno.put(turno, Math.max(0, capacidade - ocupadas));
        }

        return vagasPorTurno;
    }

    @Override
    public HorariosDisponiveisDTO buscarHorariosDisponiveis(UUID usuarioIdPublico, LocalDate data, TurnoEnum turno) {
        if (data == null || turno == null) throw new ValidationException("Informe a data e o turno da consulta.");
        if (data.isBefore(LocalDate.now(clock)))
            throw new ValidationException("Não é permitido consultar horários em data passada.");
        Usuario usuario = usuarioRepository.findByIdPublico(usuarioIdPublico)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
        escopoUnidadeService.validarUsuario(usuario);
        if (usuario.getTipoUsuario() != TipoUsuario.PROFISSIONAL
                && usuario.getTipoUsuario() != TipoUsuario.ADMINISTRADOR) {
            throw new ValidationException("O usuário selecionado não é um profissional habilitado para atendimento.");
        }
        if (bloqueioAgendaRepository.isDataBloqueadaParaUsuario(usuario, data))
            return new HorariosDisponiveisDTO(turno, 0, "AGENDA_BLOQUEADA", List.of());
        Integer capacidade = resolverCapacidade(usuario, data, data.getDayOfWeek(), turno);
        if (capacidade == null)
            return new HorariosDisponiveisDTO(turno, 0, "TURNO_NAO_CONFIGURADO", List.of());
        int ocupadas = agendamentoRepository.contarVagasOcupadasBySituacoes(usuario, data, turno, SITUACOES_ATIVAS);
        int restantes = Math.max(0, capacidade - ocupadas);
        if (restantes == 0)
            return new HorariosDisponiveisDTO(turno, 0, "CAPACIDADE_ESGOTADA", List.of());
        Set<LocalTime> ocupados = new HashSet<>(agendamentoRepository.findHorariosOcupados(usuario, data, turno, SITUACOES_ATIVAS));
        List<HorarioDisponivelDTO> horarios = new ArrayList<>();
        for (LocalTime hora : agendaHorarioProperties.grade(turno))
            horarios.add(new HorarioDisponivelDTO(hora, !ocupados.contains(hora)));
        return new HorariosDisponiveisDTO(turno, restantes, null, horarios);
    }

    /**
     * Resolve a capacidade de um turno numa data:
     * 1) Se existir DisponibilidadeExcecao para essa data+turno, ela manda (mesmo que seja 0).
     * 2) Caso contrário, cai para o padrão semanal (Disponibilidade), se existir.
     * 3) Se nenhum dos dois existir, retorna null (turno não configurado, sem vaga).
     */
    private Integer resolverCapacidade(Usuario usuario, LocalDate data, DayOfWeek diaSemana, TurnoEnum turno) {
        Optional<DisponibilidadeExcecao> excecao =
                disponibilidadeExcecaoRepository.findByUsuarioAndDataAndTurno(usuario, data, turno);

        if (excecao.isPresent()) {
            return excecao.get().getCapacidade();
        }

        Optional<Disponibilidade> padrao =
                disponibilidadeRepository.findByUsuarioAndDiaDaSemanaAndTurno(usuario, diaSemana, turno);

        return padrao.map(Disponibilidade::getCapacidade).orElse(null);
    }

    @Override
    public List<AgendamentoDTO> findAll() {
        var list = agendamentoRepository.findAll(escopoUnidadeService.<Agendamento>specificationPaciente("paciente")
                .and(escopoUnidadeService.specificationUsuario("usuario")));
        // Defense-in-depth: filtros de paciente e profissional nao provam
        // origem de cada atendimento apos mudanca de lotacao. Aplicar a
        // mesma verificacao de proveniencia usada na agenda HTTP e no GET por ID.
        return list.stream().filter(escopoUnidadeService::agendamentoPertenceAoEscopo)
                .map(agendamentoMapper::toAgendamentoDTO).toList();
    }

    @Override
    public List<LocalDate> buscarProximasVagasDisponiveis(Usuario usuario, TurnoEnum turno, LocalDate dataInicio,
                                                          int quantidadeDesejada) {
        escopoUnidadeService.validarUsuario(usuario);
        List<LocalDate> datasDisponiveis = new ArrayList<>();

        int limiteDiasBusca = 90;
        LocalDate primeiraData = dataInicio.plusDays(1);
        LocalDate ultimaData = dataInicio.plusDays(limiteDiasBusca);

        List<Disponibilidade> todasDisponibilidades = disponibilidadeRepository.findByUsuario(usuario);
        Map<DayOfWeek, Map<TurnoEnum, Disponibilidade>> disponibilidadeMap = new HashMap<>();
        for (Disponibilidade d : todasDisponibilidades) {
            disponibilidadeMap
                    .computeIfAbsent(d.getDiaDaSemana(), k -> new HashMap<>())
                    .put(d.getTurno(), d);
        }

        List<BloqueioAgenda> bloqueios = bloqueioAgendaRepository.findByUsuario(usuario);
        Set<LocalDate> datasBloqueadas = new HashSet<>();
        for (BloqueioAgenda b : bloqueios) {
            LocalDate start = b.getDataInicio();
            LocalDate end = b.getDataFim();
            if (end == null && start == null)
                continue;

            LocalDate s = (start == null || start.isBefore(primeiraData)) ? primeiraData : start;
            LocalDate e = (end == null || end.isAfter(ultimaData)) ? ultimaData : end;

            if (s.isAfter(e))
                continue;

            LocalDate cursor = s;
            while (!cursor.isAfter(e)) {
                datasBloqueadas.add(cursor);
                cursor = cursor.plusDays(1);
            }
        }

        List<Agendamento> agendamentosNoIntervalo = agendamentoRepository
                .findByUsuarioAndDataAgendamentoBetween(usuario, primeiraData, ultimaData);
        List<SituacaoAtendimento> ocupantes = List.of(SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO,
                SituacaoAtendimento.PRESENTE);

        Map<LocalDate, Map<TurnoEnum, Integer>> ocupacaoMap = new HashMap<>();
        for (Agendamento a : agendamentosNoIntervalo) {
            if (a.getDataAgendamento() == null || a.getTurnoAgendamento() == null || a.getSituacaoAtendimento() == null)
                continue;

            if (!Boolean.TRUE.equals(a.getOcupaVaga()))
                continue;

            LocalDate d = a.getDataAgendamento();
            TurnoEnum t = a.getTurnoAgendamento();

            ocupacaoMap
                    .computeIfAbsent(d, k -> new HashMap<>())
                    .merge(t, 1, Integer::sum);
        }

        LocalDate dataVerificacao = primeiraData;
        int diasBuscados = 0;

        while (datasDisponiveis.size() < quantidadeDesejada && diasBuscados < limiteDiasBusca) {
            diasBuscados++;

            if (dataVerificacao.isAfter(ultimaData))
                break;

            if (datasBloqueadas.contains(dataVerificacao)) {
                dataVerificacao = dataVerificacao.plusDays(1);
                continue;
            }

            DayOfWeek diaSemana = dataVerificacao.getDayOfWeek();

            // Antes: só olhava o padrão semanal (disponibilidadeMap). Agora resolve
            // considerando também eventuais exceções cadastradas para essa data específica.
            Integer capacidade = resolverCapacidade(usuario, dataVerificacao, diaSemana, turno);

            if (capacidade == null) {
                dataVerificacao = dataVerificacao.plusDays(1);
                continue;
            }

            int ocupadas = 0;
            Map<TurnoEnum, Integer> porTurnoCount = ocupacaoMap.get(dataVerificacao);
            if (porTurnoCount != null && porTurnoCount.get(turno) != null) {
                ocupadas = porTurnoCount.get(turno);
            }

            if (ocupadas < capacidade) {
                datasDisponiveis.add(dataVerificacao);
            }

            dataVerificacao = dataVerificacao.plusDays(1);
        }

        return datasDisponiveis;
    }

    @Override
    public List<AgendamentoDTO> buscarAgendaDoDia(LocalDate data, String emailLogado, UUID profissionalIdPublico) {
       return buscarAgendaPorPeriodo(data, data, emailLogado, profissionalIdPublico);
    }

    @Override
    public List<AgendamentoDTO> buscarAgendaPorPeriodo(LocalDate dataInicio, LocalDate dataFim, String emailLogado,
                                                       UUID profissionalIdPublico) {
        if (dataInicio.isAfter(dataFim)) {
            throw new ValidationException("A data inicial não pode ser posterior à data final.");
        }
        if (dataInicio.plusDays(LIMITE_DIAS_CONSULTA_AGENDA).isBefore(dataFim)) {
            throw new ValidationException("O intervalo da agenda não pode ultrapassar 62 dias.");
        }

        Usuario usuarioLogado = usuarioRepository.findByEmail(emailLogado)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário logado não encontrado"));

        Usuario profissionalAlvo = null;
        if (usuarioLogado.getTipoUsuario() == TipoUsuario.PROFISSIONAL) {
            if (profissionalIdPublico != null && !profissionalIdPublico.equals(usuarioLogado.getIdPublico())) {
                throw new ValidationException("Profissional não pode consultar a agenda de outro profissional.");
            }
            profissionalAlvo = usuarioLogado;
        } else if (profissionalIdPublico != null) {
            profissionalAlvo = usuarioRepository.findByIdPublico(profissionalIdPublico)
                    .orElseThrow(() -> new ResourceNotFoundException("Profissional não encontrado"));
        }

        if (profissionalAlvo != null) escopoUnidadeService.validarUsuario(profissionalAlvo);
        escopoUnidadeService.validarUnidadeConfigurada();
        List<Agendamento> agendamentos = profissionalAlvo != null
                ? agendamentoRepository.findAgendaByUsuarioAndDataAgendamentoBetween(profissionalAlvo, dataInicio, dataFim)
                : agendamentoRepository.findAgendaByDataAgendamentoBetween(dataInicio, dataFim);

        return agendamentos.stream()
                .filter(escopoUnidadeService::agendamentoPertenceAoEscopo)
                .filter(a -> a.getPaciente() != null && a.getPaciente().getStatusPaciente() == StatusPaciente.ATIVO)
                .map(agendamentoMapper::toAgendamentoDTO)
                .toList();
    }

    @Override
    @Transactional
    public AgendamentoDTO atualizarStatus(Long id, SituacaoAtendimento novoStatus, Integer expectedVersion) {
        return atualizarStatus(id, novoStatus, expectedVersion, null);
    }

    @Override
    @Transactional
    public AgendamentoDTO atualizarStatus(Long id, SituacaoAtendimento novoStatus, Integer expectedVersion, String justificativa) {
        return aplicarAtualizacao(id,novoStatus,expectedVersion,justificativa,false);
    }

    @Override
    @Transactional
    public AgendamentoDTO corrigirFrequencia(Long id,com.pet.buscaativa.entities.dto.CorrigirFrequenciaDTO dto) {
        if (dto==null || dto.novaFrequencia()==null
                || dto.novaFrequencia()==com.pet.buscaativa.entities.enums.FrequenciaAssistencial.NAO_REGISTRADA
                || dto.version()==null || dto.version()<0)
            throw new ValidationException("Informe frequência PRESENTE/FALTOU e versão não negativa para corrigir.");
        return aplicarAtualizacao(id,SituacaoAtendimento.valueOf(dto.novaFrequencia().name()),dto.version(),dto.justificativa(),true);
    }

    @Override @Transactional
    public AgendamentoDTO recuperarReservaCancelada(Long id,com.pet.buscaativa.entities.dto.RecuperarReservaCanceladaDTO dto) {
        if(dto==null)throw new ValidationException("Informe versão e justificativa da recuperação.");
        var a=bloquearAgendamento(id);autorizacaoClinica.validarAgendamento(a,true);
        validarVersaoRegularizacao(a,dto.version());validarEstadoRegularizacao(a);
        politicaTemporal.validarRecuperacao(a);String motivo=politicaJustificativa.validar(dto.justificativa());
        // Sujeitos já foram recarregados sob lock antes do atendimento.
        autorizacaoClinica.validarAgendamento(a,true);
        validarPacienteElegivelParaReserva(a.getPaciente());
        validarProfissionalElegivelParaReserva(a.getUsuario());
        validarCadeiaRegularizacao(a);historicoPacienteService.validarHistoricoRegularizacao(a,false);
        validarVagaOperacional(a.getUsuario(),a.getPaciente(),a.getDataAgendamento(),a.getTurnoAgendamento(),a.getHoraAtendimento(),id);
        int anterior=a.getVersion();a.recuperarReservaCancelada();agendamentoRepository.saveAndFlush(a);
        auditoriaRegularizacao.registrar(a,"RECUPERACAO_RESERVA",motivo,anterior);
        return agendamentoMapper.toAgendamentoDTO(a);
    }

    @Override @Transactional
    public AgendamentoDTO regularizarFrequenciaCancelada(Long id,com.pet.buscaativa.entities.dto.RegularizarFrequenciaCanceladaDTO dto) {
        if(dto==null || (dto.novaFrequencia()!=com.pet.buscaativa.entities.enums.FrequenciaAssistencial.PRESENTE
                && dto.novaFrequencia()!=com.pet.buscaativa.entities.enums.FrequenciaAssistencial.FALTOU))
            throw new ValidationException("Informe resultado PRESENTE/FALTOU comprovado, versão e justificativa.");
        var a=bloquearAgendamento(id);autorizacaoClinica.validarAgendamento(a,true);
        validarVersaoRegularizacao(a,dto.version());validarEstadoRegularizacao(a);
        politicaTemporal.validarAgendamento(a,true);String motivo=politicaJustificativa.validar(dto.justificativa());
        validarCadeiaRegularizacao(a);historicoPacienteService.validarHistoricoRegularizacao(a,false);
        if (dto.novaFrequencia() == com.pet.buscaativa.entities.enums.FrequenciaAssistencial.PRESENTE)
            validarPresencaIndividual(a);
        validarConflitosDeHorario(a.getUsuario(),a.getPaciente(),a.getDataAgendamento(),a.getHoraAtendimento(),id);
        int anterior=a.getVersion();a.registrarFatoEmReservaCancelada(dto.novaFrequencia());agendamentoRepository.saveAndFlush(a);
        pacienteService.recalcularAssiduidadePaciente(a.getPaciente());
        historicoPacienteService.registrarFatoRegularizado(a);
        auditoriaRegularizacao.registrar(a,"REGISTRO_FATO_ASSISTENCIAL",motivo,anterior);
        return agendamentoMapper.toAgendamentoDTO(a);
    }

    private void validarVersaoRegularizacao(Agendamento a,Integer versao) {
        if(versao==null || versao<0)throw new ValidationException("Informe versão esperada não negativa.");
        if(!versao.equals(a.getVersion()))throw new OptimisticLockException("O atendimento foi alterado. Atualize os dados antes de regularizar.");
    }
    private void validarEstadoRegularizacao(Agendamento a) {
        auditoriaRegularizacao.validarAusenciaDeFatoAnterior(a);
        if(a.isMigracaoPendente() || a.getSituacaoReserva()!=com.pet.buscaativa.entities.enums.SituacaoReserva.CANCELADA
                || a.getFrequenciaAssistencial()!=com.pet.buscaativa.entities.enums.FrequenciaAssistencial.NAO_REGISTRADA)
            throw new ConflictException("Regularização exige reserva cancelada com frequência não registrada e histórico identificado.");
    }
    private void validarCadeiaRegularizacao(Agendamento a) {
        if(agendamentoRepository.existsByAgendamentoOriginal_Id(a.getId()))throw cadeiaPendente(a);
        var visited=new HashSet<Long>();visited.add(a.getId());var atual=a;
        while(atual.getAgendamentoOriginal()!=null) {
            var pai=atual.getAgendamentoOriginal();
            if(!visited.add(pai.getId()) || !java.util.Objects.equals(pai.getPaciente().getId(),a.getPaciente().getId())
                || pai.isMigracaoPendente() || pai.getSituacaoReserva()!=com.pet.buscaativa.entities.enums.SituacaoReserva.SUBSTITUIDA
                || pai.getDataAgendamento()==null || pai.getHoraAtendimento()==null
                || !java.time.LocalDateTime.of(pai.getDataAgendamento(),pai.getHoraAtendimento()).isBefore(java.time.LocalDateTime.of(atual.getDataAgendamento(),atual.getHoraAtendimento()))
                || agendamentoRepository.existsByAgendamentoOriginal_IdAndIdNot(pai.getId(),atual.getId()))throw cadeiaPendente(a);
            escopoUnidadeService.validarAgendamento(pai);atual=pai;
        }
        if(atual.isRemarcacaoAposFalta())throw cadeiaPendente(a);
    }
    private com.pet.buscaativa.services.exceptions.PendenciaFrequenciaException cadeiaPendente(Agendamento a) {
        return new com.pet.buscaativa.services.exceptions.PendenciaFrequenciaException("CADEIA_REMARCACAO_PENDENTE_RECONCILIACAO","CADEIA_REMARCACAO",a.getId(),null);
    }

    private Agendamento bloquearAgendamento(Long id) {
        // Global individual lock order: patient -> professional -> appointment. Scalar identity avoids stale entities.
        var identity = agendamentoRepository.findLockIdentityById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Agendamento não encontrado"));
        agendaAssistencialLocks.bloquear(List.of(identity.getPacienteId()), List.of(identity.getUsuarioId()));
        return carregarAgendamentoBloqueado(id, identity);
    }

    private Agendamento carregarAgendamentoBloqueado(Long id, AgendamentoRepository.LockIdentity identidade) {
        Agendamento agendamento = agendamentoRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Agendamento não encontrado"));
        entityManager.refresh(agendamento, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!identidade.getPacienteId().equals(agendamento.getPaciente().getIdPublico())
                || !identidade.getUsuarioId().equals(agendamento.getUsuario().getIdPublico()))
            throw new ConflictException("A identidade do atendimento mudou; atualize a agenda antes de tentar novamente.");
        escopoUnidadeService.validarAgendamento(agendamento);
        return agendamento;
    }

    /** Single application path for the dedicated command, legacy HTTP and both legacy service overloads. */
    private AgendamentoDTO aplicarAtualizacao(Long id,SituacaoAtendimento novoStatus,Integer expectedVersion,
            String justificativa,boolean somenteCorrecao) {
        Agendamento agendamento=bloquearAgendamento(id);
        Paciente pacienteBloqueado=agendamento.getPaciente();
        Usuario profissionalBloqueado=agendamento.getUsuario();
        var frequenciaConhecida = agendamento.getFrequenciaAssistencial();
        boolean comandoClinico = novoStatus == SituacaoAtendimento.PRESENTE || novoStatus == SituacaoAtendimento.FALTOU;
        boolean fatoRegistrado = frequenciaConhecida == com.pet.buscaativa.entities.enums.FrequenciaAssistencial.PRESENTE
                || frequenciaConhecida == com.pet.buscaativa.entities.enums.FrequenciaAssistencial.FALTOU;
        autorizacaoClinica.validarAgendamento(agendamento, comandoClinico && fatoRegistrado);
        if(agendamento.getSituacaoReserva()==com.pet.buscaativa.entities.enums.SituacaoReserva.CANCELADA
                && !somenteCorrecao && !comandoClinico && novoStatus!=SituacaoAtendimento.CANCELADO)
            throw new ConflictException("Cancelamento exige operação específica de regularização, com autorização e auditoria.");
        LocalDate dataAgendamento = agendamento.getDataAgendamento();
        if (!comandoClinico && novoStatus != SituacaoAtendimento.CANCELADO && dataAgendamento != null && dataAgendamento.isAfter(LocalDate.now(clock))) {
            throw new ValidationException(
                    "Não é permitido registrar presença ou falta para agendamentos com data futura.");
        }

        if (expectedVersion != null && !expectedVersion.equals(agendamento.getVersion())) {
            throw new OptimisticLockException(
                    "O agendamento foi alterado por outro usuário. Atualize a agenda antes de tentar novamente.");
        }

        // O endpoint legado pode atualizar frequência/cancelamento, mas não
        // construir o vínculo e a cadeia de remarcação sem criar o sucessor.
        // Repetições sem mudança não fabricam nova cadeia e permanecem sem escrita.
        if ((novoStatus == SituacaoAtendimento.REMARCADO
                || novoStatus == SituacaoAtendimento.REMARCADO_ORIGEM)
                && agendamento.getSituacaoAtendimento() != novoStatus)
            throw new ConflictException("Remarcação exige a criação de sucessor pelo fluxo específico.");
        if (agendamento.getSituacaoReserva() == com.pet.buscaativa.entities.enums.SituacaoReserva.SUBSTITUIDA
                && novoStatus != agendamento.getSituacaoAtendimento()
                && (novoStatus == SituacaoAtendimento.AGENDADO
                    || novoStatus == SituacaoAtendimento.CANCELADO))
            throw new ConflictException("A reserva substituída não pode ser reaberta pelo comando de status.");

        SituacaoAtendimento statusAnterior = agendamento.getSituacaoAtendimento();
        var frequenciaAnterior = agendamento.getFrequenciaAssistencial();
        boolean destinoClinico = novoStatus == SituacaoAtendimento.PRESENTE || novoStatus == SituacaoAtendimento.FALTOU;
        boolean frequenciaRegistrada = frequenciaAnterior == com.pet.buscaativa.entities.enums.FrequenciaAssistencial.PRESENTE
                || frequenciaAnterior == com.pet.buscaativa.entities.enums.FrequenciaAssistencial.FALTOU;
        boolean correcaoDeSubstituido = statusAnterior == SituacaoAtendimento.REMARCADO_ORIGEM
                && frequenciaRegistrada && destinoClinico;
        if (agendamento.isMigracaoPendente())
            throw new ConflictException("Atendimento com informações históricas pendentes de revisão; nenhuma alteração foi realizada.");
        if (novoStatus == SituacaoAtendimento.CANCELADO && (frequenciaRegistrada
                || agendamento.getSituacaoReserva() == com.pet.buscaativa.entities.enums.SituacaoReserva.SUBSTITUIDA))
            throw new ConflictException("Cancelamento administrativo não é permitido para frequência registrada ou reserva substituída; a regularização exige fluxo clínico próprio.");
        if (somenteCorrecao && !frequenciaRegistrada)
            throw new ConflictException("A rota de correção exige frequência anterior comprovada; registro inicial possui fluxo próprio.");
        if (comandoClinico && !somenteCorrecao && agendamento.getSituacaoReserva()==com.pet.buscaativa.entities.enums.SituacaoReserva.CANCELADA)
            throw new ValidationException("Atendimento cancelado exige regularização específica; nenhuma frequência foi alterada.");
        if (frequenciaRegistrada && (novoStatus == SituacaoAtendimento.AGENDADO || novoStatus == SituacaoAtendimento.REMARCADO))
            throw new ConflictException("A frequência registrada deve ser preservada; esta alteração de reserva exige revisão.");

        if (comandoClinico) politicaTemporal.validarAgendamento(agendamento, fatoRegistrado);

        boolean correcaoCancelado=somenteCorrecao && agendamento.getSituacaoReserva()==com.pet.buscaativa.entities.enums.SituacaoReserva.CANCELADA;
        if(correcaoCancelado) {
            if(!auditoriaRegularizacao.origemFactualConfiavel(agendamento))throw new com.pet.buscaativa.services.exceptions.PendenciaFrequenciaException(
                "ORIGEM_REGULARIZACAO_PENDENTE_RECONCILIACAO","ORIGEM_REGULARIZACAO",id,null);
            historicoPacienteService.validarHistoricoRegularizacao(agendamento,true);
        }
        String motivo = somenteCorrecao ? politicaJustificativa.validar(justificativa) : null;

        if (statusAnterior == novoStatus || ((correcaoDeSubstituido || correcaoCancelado) && frequenciaAnterior.name().equals(novoStatus.name()))) {
            return agendamentoMapper.toAgendamentoDTO(agendamento);
        }


        if (motivo==null && frequenciaRegistrada && destinoClinico) motivo=politicaJustificativa.validar(justificativa);

        if ((novoStatus == SituacaoAtendimento.PRESENTE || novoStatus == SituacaoAtendimento.FALTOU)
                && ((statusAnterior == SituacaoAtendimento.CANCELADO && !correcaoCancelado) || (statusAnterior == SituacaoAtendimento.REMARCADO_ORIGEM && !correcaoDeSubstituido)))
            throw new ValidationException("Um atendimento cancelado ou substituído não permite registro de frequência.");

        // A presença efetiva não pode ignorar compromissos e fatos canônicos de outra modalidade.
        // Exclui somente o próprio atendimento; sem exigir oferta atual ou justificativa inicial.
        if (novoStatus == SituacaoAtendimento.PRESENTE)
            validarPresencaIndividual(agendamento);

        if (frequenciaAnterior == com.pet.buscaativa.entities.enums.FrequenciaAssistencial.FALTOU && novoStatus == SituacaoAtendimento.PRESENTE) {
            // The current row is missed or substituted (inactive), so it cannot conflict with itself.
            // Correct the historical fact under the same locks, independently of the current offer/grid.
            validarConflitosDeHorario(profissionalBloqueado, pacienteBloqueado, agendamento.getDataAgendamento(),
                    agendamento.getHoraAtendimento(), agendamento.getId());
        } else if (!correcaoDeSubstituido && SITUACOES_ATIVAS.contains(novoStatus) && !SITUACOES_ATIVAS.contains(statusAnterior)) {
            validarVaga(profissionalBloqueado, pacienteBloqueado, agendamento.getDataAgendamento(),
                    agendamento.getTurnoAgendamento(), agendamento.getHoraAtendimento());
        }

        int versaoAnterior=agendamento.getVersion();
        agendamento.setSituacaoAtendimento(novoStatus);

        Paciente pacienteAgendado = agendamento.getPaciente();
        if (pacienteAgendado != null) {
            Paciente paciente = pacienteBloqueado;

            pacienteService.atualizarAssiduidadePaciente(paciente, statusAnterior, novoStatus,
                    agendamento.getDataAgendamento());

            // Visit eligibility is derived by chronological recalculation, not this transition alone.
        }

        agendamento = agendamentoRepository.save(agendamento);
        if (correcaoDeSubstituido || correcaoCancelado)
            historicoPacienteService.registrarCorrecaoDeFrequencia(agendamento, frequenciaAnterior);
        else historicoPacienteService.registrarAlteracaoDeAtendimento(agendamento, statusAnterior);
        agendamentoRepository.flush();
        if(motivo!=null)auditoriaCorrecao.registrarIndividual(agendamento,frequenciaAnterior,motivo,versaoAnterior);

        return agendamentoMapper.toAgendamentoDTO(agendamento);
    }

    @Override
    public AgendamentoDTO findById(Long id, String emailLogado) {
        Usuario usuarioLogado = usuarioRepository.findByEmail(emailLogado)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário logado não encontrado"));

        Agendamento agendamento = agendamentoRepository.findByIdWithUsuarioAndPaciente(id)
                .filter(escopoUnidadeService::agendamentoPertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException("Agendamento não encontrado"));

        if (usuarioLogado.getTipoUsuario() == TipoUsuario.PROFISSIONAL
                && !agendamento.getUsuario().getIdPublico().equals(usuarioLogado.getIdPublico())) {
            throw new ValidationException("Profissional não pode consultar a agenda de outro profissional.");
        }

        return agendamentoMapper.toAgendamentoDTO(agendamento);
    }


}
