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
    private final BloqueioAgendaRepository bloqueioAgendaRepository;
    private final UsuarioRepository usuarioRepository;
    private final DisponibilidadeRepository disponibilidadeRepository;
    private final PacienteRepository pacienteRepository;
    private final DisponibilidadeExcecaoRepository disponibilidadeExcecaoRepository;

    private final AgendamentoMapper agendamentoMapper;

    private final PacienteService pacienteService;
    private final HistoricoPacienteService historicoPacienteService;
    private final AgendaHorarioProperties agendaHorarioProperties;
    private final EscopoUnidadeService escopoUnidadeService;
    private static final List<SituacaoAtendimento> SITUACOES_ATIVAS = List.of(
            SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO, SituacaoAtendimento.PRESENTE);

    @Override
    @Transactional
    public AgendamentoDTO save(AgendamentoDTO agendamentoDTO) {
        // Serializa criações do mesmo profissional entre a consulta e o save.
        Usuario usuario = usuarioRepository.findByIdPublicoForUpdate(agendamentoDTO.usuarioId())
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));

        escopoUnidadeService.validarUsuario(usuario);
        if (usuario.getTipoUsuario() != TipoUsuario.PROFISSIONAL
                && usuario.getTipoUsuario() != TipoUsuario.ADMINISTRADOR) {
            throw new ValidationException("O usuário selecionado não é um profissional habilitado para atendimento.");
        }

        Paciente paciente = pacienteRepository.findByIdPublico(agendamentoDTO.pacienteId())
                .orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado"));

        escopoUnidadeService.validarAcesso(paciente);
        if (paciente.getStatusPaciente() != StatusPaciente.ATIVO) {
            throw new ValidationException("Somente pacientes ativos podem ser agendados.");
        }
        if (agendamentoDTO.dataAgendamento().isBefore(LocalDate.now())) {
            throw new ValidationException("Não é permitido criar agendamento em data passada.");
        }

        boolean horarioJaOcupado = agendamentoRepository.existsAgendamentoAtivoNoMesmoHorario(
                usuario, agendamentoDTO.dataAgendamento(), agendamentoDTO.horaAtendimento(),
                SITUACOES_ATIVAS);

        if (horarioJaOcupado) {
            throw new ConflictException(
                    "Já existe um atendimento marcado para este profissional nesta data e horário.");
        }

        if (agendamentoRepository.existsAtivoDoPacienteNoMesmoHorario(paciente,
                agendamentoDTO.dataAgendamento(), agendamentoDTO.horaAtendimento(), SITUACOES_ATIVAS)) {
            throw new ConflictException("O paciente já possui um atendimento ativo nesta data e horário.");
        }

        if (bloqueioAgendaRepository.isDataBloqueadaParaUsuario(usuario, agendamentoDTO.dataAgendamento())) {
            throw new ConflictException("A agenda do profissional está bloqueada na data informada.");
        }
        

        // Resolve a capacidade considerando exceção de data específica (prioridade)
        // e, na ausência dela, o padrão semanal.
        Integer capacidade = resolverCapacidade(usuario, agendamentoDTO.dataAgendamento(),
                agendamentoDTO.dataAgendamento().getDayOfWeek(), agendamentoDTO.turnoAgendamento());

        if (capacidade == null || capacidade <= 0) {
            throw new ConflictException("Não existe disponibilidade para a data e turno informados.");
        }

        int ocupadas = agendamentoRepository.contarVagasOcupadasBySituacoes(usuario, agendamentoDTO.dataAgendamento(),
                agendamentoDTO.turnoAgendamento(), List.of(SituacaoAtendimento.AGENDADO,
                        SituacaoAtendimento.REMARCADO, SituacaoAtendimento.PRESENTE));
        if (ocupadas >= capacidade) {
            throw new ConflictException("Não há vagas disponíveis para a data e turno informados.");
        }

        Agendamento agendamento = new Agendamento();
        agendamento.setUsuario(usuario);
        agendamento.setPaciente(paciente);
        agendamento.setDataAgendamento(agendamentoDTO.dataAgendamento());
        agendamento.setTurnoAgendamento(agendamentoDTO.turnoAgendamento());
        agendamento.setHoraAtendimento(agendamentoDTO.horaAtendimento());

        if (agendamentoDTO.agendamentoOriginalId() != null) {
            var original = agendamentoRepository.findById(agendamentoDTO.agendamentoOriginalId())
                    .orElseThrow(() -> new ResourceNotFoundException("Agendamento original não encontrado"));

            escopoUnidadeService.validarAgendamento(original);
            if (agendamentoDTO.id() != null && agendamentoDTO.id().equals(agendamentoDTO.agendamentoOriginalId())) {
                throw new ValidationException("Um agendamento não pode ser vinculado a ele mesmo.");
            }
            if (!List.of(SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO,
                    SituacaoAtendimento.FALTOU).contains(original.getSituacaoAtendimento())) {
                throw new ConflictException("O agendamento original não está em situação compatível com remarcação.");
            }

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
        return agendamentoMapper.toAgendamentoDTO(agendamento);
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

        return buscarProximasVagasDisponiveis(original.getUsuario(), original.getTurnoAgendamento(), LocalDate.now(),
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
        if (data.isBefore(LocalDate.now()))
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
        var periodo = turno == TurnoEnum.MANHA ? agendaHorarioProperties.getHorarios().getManha()
                : agendaHorarioProperties.getHorarios().getTarde();
        Set<LocalTime> ocupados = new HashSet<>(agendamentoRepository.findHorariosOcupados(usuario, data, turno, SITUACOES_ATIVAS));
        List<HorarioDisponivelDTO> horarios = new ArrayList<>();
        for (LocalTime hora = periodo.getInicio(); !hora.isAfter(periodo.getFim());
                hora = hora.plusMinutes(agendaHorarioProperties.getIntervaloMinutos()))
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
        return list.stream().map(agendamentoMapper::toAgendamentoDTO).toList();
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

            if (!ocupantes.contains(a.getSituacaoAtendimento()))
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
        Agendamento agendamento = agendamentoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Agendamento não encontrado para o id"));

        escopoUnidadeService.validarAgendamento(agendamento);
        LocalDate dataAgendamento = agendamento.getDataAgendamento();
        if (dataAgendamento != null && dataAgendamento.isAfter(LocalDate.now())) {
            throw new ValidationException(
                    "Não é permitido registrar presença ou falta para agendamentos com data futura.");
        }

        if (expectedVersion != null && !expectedVersion.equals(agendamento.getVersion())) {
            throw new OptimisticLockException(
                    "O agendamento foi alterado por outro usuário. Atualize a agenda antes de tentar novamente.");
        }

        SituacaoAtendimento statusAnterior = agendamento.getSituacaoAtendimento();

        if (statusAnterior == novoStatus) {
            return agendamentoMapper.toAgendamentoDTO(agendamento);
        }

        boolean correcaoFrequencia = (statusAnterior == SituacaoAtendimento.PRESENTE || statusAnterior == SituacaoAtendimento.FALTOU)
                && (novoStatus == SituacaoAtendimento.PRESENTE || novoStatus == SituacaoAtendimento.FALTOU);
        if (correcaoFrequencia) {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            TipoUsuario perfil = usuarioRepository.findByEmail(email)
                    .orElseThrow(() -> new ResourceNotFoundException("Usuário logado não encontrado")).getTipoUsuario();
            if (perfil != TipoUsuario.ADMINISTRADOR && perfil != TipoUsuario.PROFISSIONAL)
                throw new ValidationException("Seu perfil não possui permissão para corrigir frequência.");
        }

        if ((novoStatus == SituacaoAtendimento.PRESENTE || novoStatus == SituacaoAtendimento.FALTOU)
                && (statusAnterior == SituacaoAtendimento.CANCELADO || statusAnterior == SituacaoAtendimento.REMARCADO_ORIGEM))
            throw new ValidationException("Um atendimento cancelado ou substituído não permite registro de frequência.");

        agendamento.setSituacaoAtendimento(novoStatus);

        Paciente pacienteAgendado = agendamento.getPaciente();
        if (pacienteAgendado != null) {
            Paciente paciente = pacienteRepository.findByIdPublicoForUpdate(pacienteAgendado.getIdPublico())
                    .orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado"));

            pacienteService.atualizarAssiduidadePaciente(paciente, statusAnterior, novoStatus,
                    agendamento.getDataAgendamento());

            // 6. RF08: Gatilho de Visita Domiciliar Automático
            // Se o status virou FALTOU e este agendamento é uma remarcação (tem um original vinculado):
            if (novoStatus == SituacaoAtendimento.FALTOU && agendamento.getAgendamentoOriginal() != null
                    && agendamento.isRemarcacaoAposFalta()) {
                paciente.setGatilhoVisitaAcionado(true);
                pacienteRepository.save(paciente);
            } else if (statusAnterior == SituacaoAtendimento.FALTOU && agendamento.getAgendamentoOriginal() != null
                    && agendamento.isRemarcacaoAposFalta()) {
                paciente.setGatilhoVisitaAcionado(false);
                pacienteRepository.save(paciente);
            }

            // O Paciente já será salvo automaticamente ao final do método por causa do @Transactional,
            // mas manter o save explícito não causa problemas se preferir.
        }

        agendamento = agendamentoRepository.save(agendamento);
        historicoPacienteService.registrarAlteracaoDeAtendimento(agendamento, statusAnterior);

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
