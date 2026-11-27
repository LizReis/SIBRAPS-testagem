package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.GrupoTerapeuticoService;
import com.pet.buscaativa.services.PacienteService;
import com.pet.buscaativa.services.HistoricoPacienteService;
import com.pet.buscaativa.services.exceptions.*;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GrupoTerapeuticoServiceImpl implements GrupoTerapeuticoService {
    private final ModeloClinico modelo;

    private final GrupoTerapeuticoRepository grupoRepository;
    private final AgendaAssistencialLocks agendaAssistencialLocks;
    private final IntegridadeAgendaAssistencialService integridadeAgendaAssistencial;
    private final SessaoGrupoRepository sessaoRepository;
    private final SessaoGrupoParticipanteRepository participanteRepository;
    private final UsuarioRepository usuarioRepository;
    private final PacienteRepository pacienteRepository;
    private final PacienteService pacienteService;
    private final HistoricoPacienteService historicoPacienteService;
    private final InscricaoRetroativaGrupoAuditoriaRepository auditoriaRetroativaRepository;
    private final AgendamentoRepository agendamentoRepository;
    private final Clock clock;
    private final DesignacaoSessaoService designacoes;
    private final com.pet.buscaativa.services.PoliticaTemporalFrequencia politicaTemporal;
    private final EscopoUnidadeService escopoUnidadeService;
    private final com.pet.buscaativa.services.AutorizacaoClinicaService autorizacaoClinica;
    private final com.pet.buscaativa.services.PoliticaJustificativaCorrecao politicaJustificativa;
    private final com.pet.buscaativa.services.AuditoriaCorrecaoService auditoriaCorrecao;
    private final jakarta.persistence.EntityManager entityManager;

    @Override
    @Transactional
    public GrupoTerapeuticoDTO criarGrupo(CriarGrupoDTO dto) {
        modelo.exigirLegado();
        validarPeriodoRecorrencia(dto);
        validarIdsParticipantes(dto.participantesIds());
        var recursos = agendaAssistencialLocks.bloquear(idsOuVazios(dto.participantesIds()), List.of(dto.coordenadorId()));
        Usuario coordenador = recursos.profissional(dto.coordenadorId());
        if (coordenador.getTipoUsuario() != TipoUsuario.PROFISSIONAL
                && coordenador.getTipoUsuario() != TipoUsuario.ADMINISTRADOR) {
            throw new ValidationException("O coordenador do grupo deve ser um profissional ou administrador.");
        }

        if (dto.dataPrimeiraSessao().isBefore(LocalDate.now(clock))) {
            throw new ValidationException("Não é permitido criar um grupo com a primeira sessão em data passada.");
        }
        var datas = gerarDatasDaSerie(dto.dataPrimeiraSessao(), dto.dataFimRecorrencia(), dto.recorrencia());
        for (LocalDate data : datas) {
            validarCompromissoCoordenador(coordenador, data, dto.horario(), null);
            for (UUID id : idsOuVazios(dto.participantesIds()))
                validarNovaParticipacao(recursos.paciente(id), data, dto.horario(), null);
        }

        GrupoTerapeutico grupo = new GrupoTerapeutico();
        grupo.setTema(dto.tema());
        grupo.setCoordenador(coordenador);
        // Registro novo: proveniência da unidade do coordenador validado e bloqueado.
        grupo.registrarUnidadeDeOrigem(coordenador);
        grupo.setRecorrencia(dto.recorrencia());
        grupo.setHorarioPadrao(dto.horario());
        grupo.setDataFimRecorrencia(dto.dataFimRecorrencia());
        grupo.setAtivo(true);

        grupo = grupoRepository.save(grupo);

        for (LocalDate data : datas) {
            SessaoGrupo sessao = novaSessao(grupo, data, dto.horario());
            sessao = sessaoRepository.save(sessao);
            for (UUID pacienteId : idsOuVazios(dto.participantesIds())) {
                adicionarParticipanteInterno(sessao, pacienteId);
            }
        }
        return toGrupoDTO(grupo);
    }

    /**
     * Gera uma série finita, inclusiva; meses são sempre calculados a partir da
     * data original.
     */
    List<LocalDate> gerarDatasDaSerie(LocalDate primeira, LocalDate fim, RecorrenciaGrupo recorrencia) {
        if (recorrencia == RecorrenciaGrupo.UNICA)
            return List.of(primeira);
        List<LocalDate> datas = new ArrayList<>();
        for (int ocorrencia = 0;; ocorrencia++) {
            LocalDate data = switch (recorrencia) {
                case SEMANAL -> primeira.plusWeeks(ocorrencia);
                case QUINZENAL -> primeira.plusWeeks(2L * ocorrencia);
                case MENSAL -> primeira.plusMonths(ocorrencia);
                case UNICA -> primeira;
            };
            if (data.isAfter(fim))
                break;
            datas.add(data);
        }
        return datas;
    }

    void validarPeriodoRecorrencia(CriarGrupoDTO dto) {
        if (dto.recorrencia() != RecorrenciaGrupo.UNICA && dto.dataFimRecorrencia() == null) {
            throw new ValidationException("A data final da recorrência é obrigatória para grupos recorrentes.");
        }
        if (dto.dataFimRecorrencia() != null && dto.dataFimRecorrencia().isBefore(dto.dataPrimeiraSessao())) {
            throw new ValidationException("A data final da recorrência não pode ser anterior à primeira sessão.");
        }
    }

    void validarIdsParticipantes(List<UUID> ids) {
        if (ids != null && new HashSet<>(ids).size() != ids.size()) {
            throw new ConflictException("A lista inicial contém paciente duplicado.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<GrupoTerapeuticoDTO> listarGrupos() {
        modelo.exigirLegado();
        return grupoRepository.findByAtivoTrue().stream().filter(escopoUnidadeService::grupoPertenceAoEscopo).map(this::toGrupoDTO).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GrupoTerapeuticoDTO buscarGrupo(Long grupoId) {
        modelo.exigirLegado();
        return toGrupoDTO(grupoRepository.findById(grupoId).filter(escopoUnidadeService::grupoPertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado.")));
    }

    @Override
    @Transactional
    public GrupoTerapeuticoDTO atualizarGrupo(Long grupoId, AtualizarGrupoTerapeuticoDTO dto) {
        modelo.exigirLegado();
        UUID coordenadorOrigem = coordenadorEscalar(grupoId);
        // Transferência e confirmação compartilham os pacientes antes de ler/
        // bloquear responsáveis. Não impor elegibilidade nova ao paciente histórico.
        boolean regeneracaoPrevista = alteracaoEstruturalEscalar(grupoId, dto);
        boolean transferenciaPrevista = !coordenadorOrigem.equals(dto.coordenadorId());
        Set<UUID> participantesBloqueados = regeneracaoPrevista || transferenciaPrevista
                ? participantesSerieEscalar(grupoId) : Set.of();
        if (!regeneracaoPrevista && transferenciaPrevista)
            agendaAssistencialLocks.bloquearPacientesParaTransferencia(participantesBloqueados);
        var recursos = agendaAssistencialLocks.bloquear(regeneracaoPrevista ? participantesBloqueados : Set.of(),
                List.of(coordenadorOrigem, dto.coordenadorId()));
        GrupoTerapeutico grupo = bloquearGrupoAposSujeitos(grupoId);
        autorizacaoClinica.validarEdicaoGrupo(grupo);
        if (!dto.version().equals(grupo.getVersion()))
            throw new OptimisticLockException("O grupo foi alterado por outro usuário.");
        if (!coordenadorOrigem.equals(grupo.getCoordenador().getIdPublico()))
            throw new ConflictException("A coordenação foi alterada; atualize o grupo antes de tentar novamente.");
        Usuario coordenador = recursos.profissional(dto.coordenadorId());
        escopoUnidadeService.validarUsuario(coordenador);
        if (coordenador.getTipoUsuario() != TipoUsuario.PROFISSIONAL && coordenador.getTipoUsuario() != TipoUsuario.ADMINISTRADOR)
            throw new ValidationException("O coordenador deve ser profissional ou administrador.");
        List<SessaoGrupo> sessoes = sessoesParaOperacaoLocal(grupo);
        LocalDateTime agora = LocalDateTime.now(clock);
        boolean iniciado = sessoes.stream().anyMatch(s -> s.getStatus() != StatusSessaoGrupo.AGENDADA
                || !LocalDateTime.of(s.getDataSessao(), s.getHorario()).isAfter(agora));
        SessaoGrupo primeira = sessoes.stream().min(Comparator.comparing(SessaoGrupo::getDataSessao).thenComparing(SessaoGrupo::getHorario))
                .orElseThrow(() -> new ValidationException("Grupo sem sessões."));
        boolean estrutural = !primeira.getDataSessao().equals(dto.dataPrimeiraSessao())
                || !grupo.getHorarioPadrao().equals(dto.horario()) || grupo.getRecorrencia() != dto.recorrencia()
                || !java.util.Objects.equals(grupo.getDataFimRecorrencia(), dto.dataFimRecorrencia());
        if ((estrutural && !regeneracaoPrevista)
                || ((estrutural || transferenciaPrevista) && !participantesBloqueados.equals(participantesSerieEscalar(grupoId))))
            throw new ConflictException("A estrutura ou os participantes foram alterados; atualize o grupo antes de tentar novamente.");
        if (iniciado && estrutural) {
            sessoes.forEach(escopoUnidadeService::validarSessaoCompleta);
            throw new ConflictException("Após o início do grupo, data, horário e recorrência não podem ser alterados.");
        }
        if (!iniciado && estrutural) {
            sessoes.forEach(escopoUnidadeService::validarSessaoCompleta);
            Set<UUID> participantes = sessoes.isEmpty() ? Set.of() : sessoes.get(0).getParticipantes().stream()
                    .map(p -> p.getPaciente().getIdPublico()).collect(java.util.stream.Collectors.toSet());
            boolean listasDiferentes = sessoes.stream().anyMatch(s -> !s.getParticipantes().stream()
                    .map(p -> p.getPaciente().getIdPublico()).collect(java.util.stream.Collectors.toSet()).equals(participantes));
            if (listasDiferentes) throw new ConflictException("A série possui listas de participantes diferentes e não pode ser regenerada com segurança.");
            CriarGrupoDTO validacao = new CriarGrupoDTO(dto.tema(), dto.coordenadorId(), dto.recorrencia(),
                    dto.dataPrimeiraSessao(), dto.dataFimRecorrencia(), dto.horario(), List.copyOf(participantes));
            validarPeriodoRecorrencia(validacao);
            if (dto.dataPrimeiraSessao().isBefore(LocalDate.now(clock))) throw new ValidationException("A primeira sessão não pode estar no passado.");
            List<LocalDate> datas = gerarDatasDaSerie(dto.dataPrimeiraSessao(), dto.dataFimRecorrencia(), dto.recorrencia());
            // Excluir exclusivamente a sessão antiga que será substituída naquele instante.
            // Não ocultar outras sessões da mesma série ou de outros grupos.
            Map<IntegridadeAgendaAssistencialService.Instante, Long> origens = new java.util.HashMap<>();
            for (SessaoGrupo antiga : sessoes) {
                var instante = new IntegridadeAgendaAssistencialService.Instante(antiga.getDataSessao(), antiga.getHorario());
                if (origens.putIfAbsent(instante, antiga.getId()) != null)
                    throw new ConflictException("A série possui sessões simultâneas e exige revisão antes da regeneração.");
            }
            for (LocalDate data : datas) {
                Long excluir = origens.get(new IntegridadeAgendaAssistencialService.Instante(data, dto.horario()));
                validarCompromissoCoordenador(coordenador, data, dto.horario(), excluir);
                for (UUID pacienteId : participantes)
                    validarNovaParticipacao(recursos.paciente(pacienteId), data, dto.horario(), excluir);
            }
            sessaoRepository.deleteAll(sessoes);
            sessaoRepository.flush();
            for (LocalDate data : datas) {
                SessaoGrupo nova = novaSessao(grupo, data, dto.horario());
                nova.designarProfissional(coordenador);
                sessaoRepository.save(nova);
                for (UUID pacienteId : participantes) adicionarParticipanteInterno(nova, pacienteId);
            }
        }
        if (!estrutural && !coordenadorOrigem.equals(dto.coordenadorId())) {
            for (SessaoGrupo sessao : sessoes) {
                if (sessao.getStatus() == StatusSessaoGrupo.AGENDADA && !sessao.isResponsabilidadePendente()
                        && designacoes.aindaNaoIniciou(sessao, agora))
                    validarCompromissoCoordenador(coordenador, sessao.getDataSessao(), sessao.getHorario(), sessao.getId());
            }
            sessoes.forEach(sessao -> designacoes.transferir(sessao, coordenador, agora));
        }
        grupo.setTema(dto.tema().trim()); grupo.setCoordenador(coordenador); grupo.setHorarioPadrao(dto.horario());
        grupo.setRecorrencia(dto.recorrencia()); grupo.setDataFimRecorrencia(dto.dataFimRecorrencia());
        return toGrupoDTO(grupoRepository.saveAndFlush(grupo));
    }

    @Override
    @Transactional(readOnly = true)
    public LocalDate sugerirProximaData(Long grupoId) {
        modelo.exigirLegado();
        GrupoTerapeutico grupo = grupoRepository.findById(grupoId).filter(escopoUnidadeService::grupoPertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));

        LocalDate base = sessaoRepository.findByGrupoOrderByDataSessaoDesc(grupo).stream()
                .filter(escopoUnidadeService::sessaoPertenceAoEscopo)
                .map(SessaoGrupo::getDataSessao).findFirst().orElse(LocalDate.now(clock));

        return switch (grupo.getRecorrencia()) {
            case SEMANAL -> base.plusWeeks(1);
            case QUINZENAL -> base.plusWeeks(2);
            case MENSAL -> base.plusMonths(1);
            case UNICA -> base;
        };
    }

    @Override
    @Transactional
    public SessaoGrupoDTO criarProximaSessao(NovaSessaoDTO dto) {
        modelo.exigirLegado();
        validarIdsParticipantes(dto.participantesIds());
        GrupoTerapeutico grupo = bloquearGrupoComSujeitos(dto.grupoId(), idsOuVazios(dto.participantesIds()));

        if (!grupo.isAtivo())
            throw new ValidationException("Não é possível criar sessões para um grupo inativo.");
        if (dto.dataSessao().isBefore(LocalDate.now(clock)))
            throw new ValidationException("Não é permitido criar sessão em data passada.");
        boolean existe = sessoesParaOperacaoLocal(grupo).stream()
                .anyMatch(s -> s.getDataSessao().equals(dto.dataSessao())
                        && s.getStatus() != StatusSessaoGrupo.CANCELADA);
        if (existe)
            throw new ConflictException("Já existe uma sessão agendada para este grupo nesta data.");
        LocalTime horario = dto.horario() == null ? grupo.getHorarioPadrao() : dto.horario();
        validarCompromissoCoordenador(grupo.getCoordenador(), dto.dataSessao(), horario, null);
        for (UUID id : idsOuVazios(dto.participantesIds()))
            validarNovaParticipacao(pacienteRepository.findByIdPublico(id).orElseThrow(), dto.dataSessao(), horario, null);
        SessaoGrupo sessao = sessaoRepository.save(novaSessao(grupo, dto.dataSessao(), horario));
        for (UUID id : idsOuVazios(dto.participantesIds()))
            adicionarParticipanteInterno(sessao, id);

        return toSessaoDTO(sessao);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessaoGrupoDTO> listarSessoes(LocalDate inicio, LocalDate fim) {
        modelo.exigirLegado();
        if (inicio.isAfter(fim))
            throw new ValidationException("A data inicial não pode ser posterior à data final.");
        return sessaoRepository.findByDataSessaoBetween(inicio, fim).stream()
                .filter(escopoUnidadeService::sessaoPertenceAoEscopo).map(this::toSessaoDTO).toList();
    }

    @Override
    @Transactional
    public SessaoGrupoDTO adicionarParticipante(Long sessaoId, AdicionarParticipanteDTO dto) {
        modelo.exigirLegado();
        SessaoGrupo sessao = bloquearSessao(sessaoId, List.of(dto.pacienteId()), FinalidadeBloqueioSessao.OPERACIONAL);

        validarSessaoEditavel(sessao);
        var paciente = pacienteRepository.findByIdPublico(dto.pacienteId()).orElseThrow();
        validarNovaParticipacao(paciente, sessao.getDataSessao(), sessao.getHorario(), sessao.getId());

        adicionarParticipanteInterno(sessao, dto.pacienteId());

        return toSessaoDTO(sessao);
    }

    @Override
    @Transactional
    public SessaoGrupoDTO removerParticipante(Long sessaoId, UUID pacienteId) {
        modelo.exigirLegado();
        SessaoGrupo sessao = bloquearSessao(sessaoId, List.of(pacienteId), FinalidadeBloqueioSessao.OPERACIONAL);

        validarSessaoEditavel(sessao);

        SessaoGrupoParticipante participante = buscarParticipante(sessao, pacienteId);

        sessao.getParticipantes().remove(participante);
        participanteRepository.delete(participante);

        return toSessaoDTO(sessao);
    }

    @Override
    @Transactional
    public SessaoGrupoDTO registrarPresenca(Long sessaoId, UUID pacienteId, RegistrarPresencaGrupoDTO dto) {
        modelo.exigirLegado();
        SessaoGrupo sessao = bloquearSessao(sessaoId, List.of(pacienteId), FinalidadeBloqueioSessao.CLINICA);
        autorizacaoClinica.validarSessao(sessao);
        if (sessao.getStatus() != StatusSessaoGrupo.AGENDADA)
            throw new ValidationException("A frequência provisória só pode ser alterada em sessão agendada.");
        designacoes.exigirSemRevisao(sessao);
        politicaTemporal.validarSessao(sessao, false);
        SessaoGrupoParticipante participante = buscarParticipante(sessao, pacienteId);
        participante.setStatusPresenca(dto.statusPresenca());
        participanteRepository.save(participante);
        return toSessaoDTO(sessao);
    }

    @Override
    @Transactional
    public SessaoGrupoDTO atualizarStatus(Long sessaoId, StatusSessaoGrupo novoStatus, Integer expectedVersion) {
        modelo.exigirLegado();
        SessaoGrupo sessao = bloquearSessao(sessaoId, List.of(), FinalidadeBloqueioSessao.CLINICA);
        autorizacaoClinica.validarSessao(sessao);
        if (expectedVersion != null && !expectedVersion.equals(sessao.getVersion()))
            throw new OptimisticLockException(
                    "A sessão foi alterada por outro usuário. Atualize a tela antes de tentar novamente.");
        if (novoStatus == StatusSessaoGrupo.REALIZADA || novoStatus == StatusSessaoGrupo.CANCELADA)
            throw new ValidationException("Use a confirmação de ocorrência para realizar ou cancelar uma sessão.");
        if (sessao.getStatus() != StatusSessaoGrupo.AGENDADA || novoStatus != StatusSessaoGrupo.AGENDADA)
            throw new ValidationException("Transição de status não permitida.");
        return toSessaoDTO(sessao);
    }

    @Override
    @Transactional
    public SessaoGrupoDTO confirmarOcorrencia(Long sessaoId, ConfirmarOcorrenciaGrupoDTO dto) {
        modelo.exigirLegado();
        SessaoGrupo sessao = bloquearSessao(sessaoId, List.of(), FinalidadeBloqueioSessao.CLINICA);
        autorizacaoClinica.validarSessao(sessao);
        if (sessao.getStatus() != StatusSessaoGrupo.AGENDADA)
            throw new ConflictException("Sessão já foi confirmada como " + sessao.getStatus() + ".");
        if (dto.version() != null && !dto.version().equals(sessao.getVersion()))
            throw new OptimisticLockException("A sessão foi alterada por outro usuário.");
        designacoes.exigirSemRevisao(sessao);
        if (!dto.ocorreu()) {
            for (var p : sessao.getParticipantes())
                p.setStatusPresenca(StatusPresencaGrupo.NAO_REGISTRADA);
            participanteRepository.saveAll(sessao.getParticipantes());
            sessao.setMotivoCancelamento(dto.motivoCancelamento());
            sessao.setStatus(StatusSessaoGrupo.CANCELADA);
            return toSessaoDTO(sessaoRepository.save(sessao));
        }
        politicaTemporal.validarSessao(sessao, false);
        var responsavel = designacoes.paraConfirmacao(sessao);
        List<FrequenciaParticipanteGrupoDTO> recebidas = dto.frequencias() == null ? List.of() : dto.frequencias();
        Set<UUID> ids = new HashSet<>();
        for (var f : recebidas) {
            if (!ids.add(f.pacienteId()))
                throw new ConflictException("Paciente duplicado nas frequências.");
            if (f.statusPresenca() != StatusPresencaGrupo.PRESENTE && f.statusPresenca() != StatusPresencaGrupo.FALTOU)
                throw new ValidationException("A confirmação aceita somente PRESENTE ou FALTOU.");
        }
        Map<UUID, StatusPresencaGrupo> mapa = recebidas.stream().collect(java.util.stream.Collectors.toMap(
                FrequenciaParticipanteGrupoDTO::pacienteId, FrequenciaParticipanteGrupoDTO::statusPresenca));
        Set<UUID> inscritos = sessao.getParticipantes().stream().map(p -> p.getPaciente().getIdPublico())
                .collect(java.util.stream.Collectors.toSet());
        if (!inscritos.containsAll(mapa.keySet()))
            throw new ValidationException("Uma frequência informada não pertence à sessão.");
        List<SessaoGrupoParticipante> participantes = sessao.getParticipantes().stream()
                .sorted(Comparator.comparing(p -> p.getPaciente().getIdPublico())).toList();
        // A realização compromete o responsável mesmo com lista vazia ou só faltas.
        validarRealizacaoColetiva(sessao);
        for (var p : participantes)
            if (mapa.getOrDefault(p.getPaciente().getIdPublico(), StatusPresencaGrupo.FALTOU) == StatusPresencaGrupo.PRESENTE)
                validarPresencaColetiva(p.getPaciente(), sessao);
        List<Paciente> pacientes = new ArrayList<>();
        // Todos os pacientes já foram adquiridos antes dos profissionais/grupo/sessão.
        for (var p : participantes) {
            Paciente bloqueado = p.getPaciente();
            p.setStatusPresenca(mapa.getOrDefault(bloqueado.getIdPublico(), StatusPresencaGrupo.FALTOU));
            pacientes.add(bloqueado);
        }
        participanteRepository.saveAll(participantes);
        sessao.setMotivoCancelamento(null);
        sessao.confirmarRealizacao(responsavel);
        sessaoRepository.saveAndFlush(sessao);
        for (Paciente p : pacientes)
            pacienteService.recalcularAssiduidadePaciente(p);
        for (var p : participantes)
            historicoPacienteService.registrarFrequenciaGrupo(p.getPaciente(), sessao, p.getStatusPresenca());
        return toSessaoDTO(sessao);
    }

    @Override
    @Transactional
    public SessaoGrupoDTO inscreverRetroativamente(Long grupoId, InscricaoRetroativaGrupoDTO dto) {
        modelo.exigirLegado();
        agendaAssistencialLocks.bloquear(List.of(dto.pacienteId()), List.of());
        UUID coordenador = coordenadorEscalar(grupoId);
        Set<UUID> historicos = responsaveisHistoricosEscalares(grupoId);
        agendaAssistencialLocks.bloquearResponsaveisClinicos(List.of(coordenador), historicos);
        GrupoTerapeutico grupo = bloquearGrupoAposSujeitos(grupoId);
        autorizacaoClinica.validarGrupoClinico(grupo);
        if (!coordenador.equals(grupo.getCoordenador().getIdPublico())
                || !historicos.equals(responsaveisHistoricosEscalares(grupoId)))
            throw new ConflictException("A responsabilidade da série foi alterada; atualize antes de tentar novamente.");
        Paciente paciente = pacienteRepository.findByIdPublico(dto.pacienteId()).orElseThrow();
        // Identidade global nao concede acesso clinico: a inscricao
        // retroativa altera frequencias, historico e indicadores do paciente.
        escopoUnidadeService.validarAcesso(paciente);
        autorizacaoClinica.validarGrupoClinico(grupo);
        if (paciente.getStatusPaciente() != StatusPaciente.ATIVO)
            throw new ValidationException("Somente pacientes ativos podem ser inscritos.");
        List<SessaoGrupo> sessoes = sessoesParaOperacaoLocal(grupo);
        Map<Long, StatusPresencaGrupo> informadas = new java.util.HashMap<>();
        for (var f : dto.frequenciasPassadas()) {
            if (informadas.put(f.sessaoId(), f.statusPresenca()) != null)
                throw new ConflictException("Sessão repetida no payload.");
            if (f.statusPresenca() != StatusPresencaGrupo.PRESENTE && f.statusPresenca() != StatusPresencaGrupo.FALTOU)
                throw new ValidationException("Frequências retroativas aceitam somente PRESENTE ou FALTOU.");
        }
        Set<Long> idsGrupo = sessoes.stream().map(SessaoGrupo::getId).collect(java.util.stream.Collectors.toSet());
        if (!idsGrupo.containsAll(informadas.keySet()))
            throw new ValidationException("Sessão informada não pertence ao grupo.");
        LocalDateTime agora = LocalDateTime.now(clock);
        sessoes.stream().filter(s -> s.getStatus()==StatusSessaoGrupo.REALIZADA).forEach(s -> {
            autorizacaoClinica.validarSessao(s); politicaTemporal.validarSessao(s,false);
        });
        List<SessaoGrupo> realizadas = sessoes.stream().filter(s -> s.getStatus() == StatusSessaoGrupo.REALIZADA
                && !LocalDateTime.of(s.getDataSessao(), s.getHorario()).isAfter(agora)).toList();
        if (!informadas.keySet()
                .equals(realizadas.stream().map(SessaoGrupo::getId).collect(java.util.stream.Collectors.toSet())))
            throw new ValidationException("Informe a frequência de todas e somente as sessões passadas realizadas.");
        for (SessaoGrupo s : sessoes)
            if (s.getStatus() == StatusSessaoGrupo.AGENDADA
                    && LocalDateTime.of(s.getDataSessao(), s.getHorario()).isBefore(agora))
                throw new ConflictException(
                        "A sessão " + s.getId() + " precisa ser confirmada antes da inscrição retroativa.");
        for (SessaoGrupo s : sessoes) {
            var existente = participanteRepository.findBySessaoGrupoAndPaciente(s, paciente);
            if (existente.isPresent() && (s.getStatus() == StatusSessaoGrupo.REALIZADA
                    || existente.get().getStatusPresenca() != StatusPresencaGrupo.NAO_REGISTRADA))
                throw new ConflictException("Paciente já possui frequência na sessão " + s.getId() + ".");
        }
        // Pré-validação factual e operacional de todo o lote, antes de gravar.
        for (SessaoGrupo s : realizadas)
            if (informadas.get(s.getId()) == StatusPresencaGrupo.PRESENTE)
                validarPresencaColetiva(paciente, s);
        for (SessaoGrupo s : sessoes) {
            if (s.getStatus() == StatusSessaoGrupo.AGENDADA
                    && participanteRepository.findBySessaoGrupoAndPaciente(s, paciente).isEmpty())
                validarNovaParticipacao(paciente, s.getDataSessao(), s.getHorario(), s.getId());
        }
        InscricaoRetroativaGrupoAuditoria audit = new InscricaoRetroativaGrupoAuditoria();
        audit.setGrupo(grupo);
        audit.setPaciente(paciente);
        for (SessaoGrupo s : sessoes) {
            if (s.getStatus() == StatusSessaoGrupo.CANCELADA)
                continue;
            SessaoGrupoParticipante p = participanteRepository.findBySessaoGrupoAndPaciente(s, paciente)
                    .orElseGet(() -> {
                        SessaoGrupoParticipante novo = new SessaoGrupoParticipante();
                        novo.setSessaoGrupo(s);
                        novo.setPaciente(paciente);
                        return novo;
                    });
            p.setStatusPresenca(s.getStatus() == StatusSessaoGrupo.REALIZADA ? informadas.get(s.getId())
                    : StatusPresencaGrupo.NAO_REGISTRADA);
            participanteRepository.save(p);
            if (!s.getParticipantes().contains(p))
                s.getParticipantes().add(p);
            if (s.getStatus() == StatusSessaoGrupo.REALIZADA) {
                InscricaoRetroativaGrupoFrequenciaAuditoria item = new InscricaoRetroativaGrupoFrequenciaAuditoria();
                item.setAuditoria(audit);
                item.setSessao(s);
                item.setDataSessao(s.getDataSessao());
                item.setStatusPresenca(p.getStatusPresenca());
                audit.getFrequencias().add(item);
            }
        }
        participanteRepository.flush();
        pacienteService.recalcularAssiduidadePaciente(paciente);
        for (SessaoGrupo s : realizadas)
            historicoPacienteService.registrarFrequenciaGrupo(paciente, s, informadas.get(s.getId()));
        auditoriaRetroativaRepository.save(audit);
        return toSessaoDTO(sessoes.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessaoInscricaoRetroativaDTO> listarSessoesParaInscricaoRetroativa(Long grupoId) {
        modelo.exigirLegado();
        GrupoTerapeutico g = grupoRepository.findById(grupoId).filter(escopoUnidadeService::grupoPertenceAoEscopo).orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
        return sessaoRepository.findByGrupoOrderByDataSessaoDesc(g).stream()
                .filter(escopoUnidadeService::sessaoPertenceAoEscopo)
                .map(s -> new SessaoInscricaoRetroativaDTO(
                s.getId(), s.getDataSessao(), s.getHorario(), s.getStatus(), statusExibicao(s), s.getStatus() == StatusSessaoGrupo.REALIZADA)).toList();
    }

    @Override
    @Transactional
    public SessaoGrupoDTO inscreverEmSessoesFuturas(Long grupoId, InscricaoFuturaGrupoDTO dto) {
        modelo.exigirLegado();
        GrupoTerapeutico grupo = bloquearGrupoComSujeitos(grupoId, List.of(dto.pacienteId()));
        Paciente paciente = pacienteRepository.findByIdPublico(dto.pacienteId()).orElseThrow();
        // Verificar unidade ANTES de avaliar vagas e alterar inscricoes.
        escopoUnidadeService.validarAcesso(paciente);
        if (paciente.getStatusPaciente() != StatusPaciente.ATIVO) throw new ValidationException("Somente pacientes ativos podem ser inscritos.");
        LocalDateTime agora = LocalDateTime.now(clock);
        List<SessaoGrupo> futuras = sessoesParaOperacaoLocal(grupo).stream()
                .filter(s -> s.getStatus() == StatusSessaoGrupo.AGENDADA && LocalDateTime.of(s.getDataSessao(), s.getHorario()).isAfter(agora))
                .sorted(Comparator.comparing(SessaoGrupo::getDataSessao).thenComparing(SessaoGrupo::getHorario)).toList();
        if (futuras.isEmpty()) throw new ValidationException("Não existem sessões futuras para inscrição.");
        for (SessaoGrupo s : futuras) {
            if (participanteRepository.findBySessaoGrupoAndPaciente(s, paciente).isEmpty())
                validarNovaParticipacao(paciente, s.getDataSessao(), s.getHorario(), s.getId());
            if (agendamentoRepository.existsAtivoDoPacienteNoMesmoHorario(paciente, s.getDataSessao(), s.getHorario(),
                    List.of(SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO)))
                throw new ConflictException("Já existe uma reserva para o paciente em " + s.getDataSessao() + " às " + s.getHorario() + ".");
        }
        for (SessaoGrupo s : futuras) if (participanteRepository.findBySessaoGrupoAndPaciente(s, paciente).isEmpty())
            adicionarParticipanteInterno(s, paciente.getIdPublico());
        return toSessaoDTO(futuras.get(0));
    }

    @Override
    @Transactional
    public void removerParticipanteDoGrupo(Long grupoId, UUID pacienteId) {
        modelo.exigirLegado();
        GrupoTerapeutico grupo = bloquearGrupoComSujeitos(grupoId, List.of(pacienteId));
        Paciente paciente = pacienteRepository.findByIdPublico(pacienteId).orElseThrow();
        // Nao remover inscricao de paciente de outra unidade mesmo que um
        // grupo legado contenha uma participacao clinica inconsistente.
        escopoUnidadeService.validarAcesso(paciente);
        LocalDateTime agora = LocalDateTime.now(clock);
        List<SessaoGrupoParticipante> remover = sessoesParaOperacaoLocal(grupo).stream()
                .filter(s -> s.getStatus() == StatusSessaoGrupo.AGENDADA && LocalDateTime.of(s.getDataSessao(), s.getHorario()).isAfter(agora))
                .map(s -> participanteRepository.findBySessaoGrupoAndPaciente(s, paciente).orElse(null)).filter(java.util.Objects::nonNull).toList();
        if (remover.isEmpty()) throw new ValidationException("Não existem inscrições futuras para remover.");
        for (var p : remover) p.getSessaoGrupo().getParticipantes().remove(p);
        participanteRepository.deleteAll(remover);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ParticipanteGrupoDTO> listarParticipantesDoGrupo(Long grupoId) {
        modelo.exigirLegado();
        GrupoTerapeutico grupo = grupoRepository.findById(grupoId).filter(escopoUnidadeService::grupoPertenceAoEscopo).orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
        LocalDateTime agora = LocalDateTime.now(clock);
        return sessaoRepository.findByGrupoOrderByDataSessaoDesc(grupo).stream()
                .filter(escopoUnidadeService::sessaoPertenceAoEscopo)
                .flatMap(s -> s.getParticipantes().stream())
                .filter(p -> escopoUnidadeService.pacientePertenceAoEscopo(p.getPaciente()))
                .collect(java.util.stream.Collectors.groupingBy(p -> p.getPaciente().getIdPublico())).values().stream().map(lista -> {
                    var paciente = lista.get(0).getPaciente();
                    var finais = lista.stream().filter(p -> p.getSessaoGrupo().getStatus() == StatusSessaoGrupo.REALIZADA
                            && p.getStatusPresenca() != StatusPresencaGrupo.NAO_REGISTRADA).toList();
                    long presencas = finais.stream().filter(p -> p.getStatusPresenca() == StatusPresencaGrupo.PRESENTE).count();
                    long faltas = finais.size() - presencas;
                    LocalDate desde = lista.stream().map(p -> p.getSessaoGrupo().getDataSessao()).min(LocalDate::compareTo).orElse(null);
                    boolean futura = lista.stream().anyMatch(p -> p.getSessaoGrupo().getStatus() == StatusSessaoGrupo.AGENDADA
                            && LocalDateTime.of(p.getSessaoGrupo().getDataSessao(), p.getSessaoGrupo().getHorario()).isAfter(agora));
                    return new ParticipanteGrupoDTO(paciente.getIdPublico(), paciente.getNome(), desde, finais.size(), presencas, faltas,
                            finais.isEmpty() ? null : presencas * 100.0 / finais.size(), futura);
                }).sorted(Comparator.comparing(ParticipanteGrupoDTO::nomePaciente)).toList();
    }

    @Override
    @Transactional
    public SessaoGrupoDTO corrigirFrequencias(Long sessaoId, CorrigirFrequenciasGrupoDTO dto) {
        modelo.exigirLegado();
        SessaoGrupo sessao = bloquearSessao(sessaoId, List.of(), FinalidadeBloqueioSessao.CLINICA);
        autorizacaoClinica.validarSessao(sessao);
        if (sessao.getStatus() != StatusSessaoGrupo.REALIZADA) throw new ValidationException("Somente sessões realizadas permitem correção de frequência.");
        if (dto.version() != null && !dto.version().equals(sessao.getVersion())) throw new OptimisticLockException("A sessão foi alterada por outro usuário.");
        politicaTemporal.validarSessao(sessao, true);
        if(dto.frequencias()==null || dto.frequencias().isEmpty())throw new ValidationException("Informe as frequências para correção.");
        Set<UUID> ids = new HashSet<>(); List<SessaoGrupoParticipante> alterados = new ArrayList<>();
        Map<UUID,StatusPresencaGrupo> destinos = new java.util.HashMap<>();
        for (var f : dto.frequencias()) {
            if (f==null || f.pacienteId()==null || !ids.add(f.pacienteId())) throw new ConflictException("Participante inválido ou duplicado nas correções.");
            if (f.statusPresenca() != StatusPresencaGrupo.PRESENTE && f.statusPresenca() != StatusPresencaGrupo.FALTOU)
                throw new ValidationException("A correção aceita somente PRESENTE ou FALTOU.");
            SessaoGrupoParticipante p = buscarParticipante(sessao, f.pacienteId());
            if(p.getStatusPresenca()==StatusPresencaGrupo.NAO_REGISTRADA)throw new ConflictException("Frequência histórica não registrada exige revisão; nenhuma correção foi realizada.");
            if(p.getStatusPresenca()!=f.statusPresenca()){alterados.add(p);destinos.put(f.pacienteId(),f.statusPresenca());}
        }
        if(alterados.isEmpty())return toSessaoDTO(sessao);
        String motivo=politicaJustificativa.validar(dto.justificativa());
        int versaoAnterior=sessao.getVersion();
        Map<Long,StatusPresencaGrupo> anteriores=new java.util.HashMap<>();
        for(var p:alterados) {
            anteriores.put(p.getId(),p.getStatusPresenca());
            if (destinos.get(p.getPaciente().getIdPublico()) == StatusPresencaGrupo.PRESENTE)
                validarPresencaColetiva(p.getPaciente(), sessao);
            historicoPacienteService.validarHistoricoCorrecaoGrupo(p.getPaciente(), sessao, p.getStatusPresenca());
        }
        entityManager.lock(sessao,jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        for(var p:alterados)p.setStatusPresenca(destinos.get(p.getPaciente().getIdPublico()));
        participanteRepository.saveAll(alterados); sessaoRepository.saveAndFlush(sessao);
        for (var p : alterados) {
            pacienteService.recalcularAssiduidadePaciente(p.getPaciente());
            historicoPacienteService.corrigirFrequenciaGrupo(p.getPaciente(), sessao, p.getStatusPresenca());
            auditoriaCorrecao.registrarGrupo(sessao,p,anteriores.get(p.getId()),motivo,versaoAnterior);
        }
        return toSessaoDTO(sessao);
    }

    /** Descoberta escalar; a coordenação deve ser reconferida após a espera pelos locks. */
    private UUID coordenadorEscalar(Long grupoId) {
        return entityManager.createQuery("select g.coordenador.idPublico from GrupoTerapeutico g where g.id=:id", UUID.class)
                .setParameter("id", grupoId).getResultList().stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
    }

    private boolean alteracaoEstruturalEscalar(Long grupoId, AtualizarGrupoTerapeuticoDTO dto) {
        Object[] dados = entityManager.createQuery(
                "select g.horarioPadrao, g.recorrencia, g.dataFimRecorrencia from GrupoTerapeutico g where g.id=:id",
                Object[].class).setParameter("id", grupoId).getResultList().stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
        LocalDate primeira = entityManager.createQuery(
                "select min(s.dataSessao) from SessaoGrupo s where s.grupo.id=:id", LocalDate.class)
                .setParameter("id", grupoId).getSingleResult();
        return !java.util.Objects.equals(primeira, dto.dataPrimeiraSessao())
                || !java.util.Objects.equals(dados[0], dto.horario())
                || dados[1] != dto.recorrencia()
                || !java.util.Objects.equals(dados[2], dto.dataFimRecorrencia());
    }

    private Set<UUID> participantesSerieEscalar(Long grupoId) {
        return new HashSet<>(entityManager.createQuery(
                "select distinct p.paciente.idPublico from SessaoGrupoParticipante p where p.sessaoGrupo.grupo.id=:id",
                UUID.class).setParameter("id", grupoId).getResultList());
    }

    private GrupoTerapeutico bloquearGrupoComSujeitos(Long grupoId, java.util.Collection<UUID> pacientes) {
        UUID coordenador = coordenadorEscalar(grupoId);
        agendaAssistencialLocks.bloquear(pacientes, List.of(coordenador));
        var grupo = bloquearGrupoAposSujeitos(grupoId);
        if (!coordenador.equals(grupo.getCoordenador().getIdPublico()))
            throw new ConflictException("A coordenação foi alterada; atualize o grupo antes de tentar novamente.");
        return grupo;
    }

    private GrupoTerapeutico bloquearGrupoAposSujeitos(Long grupoId) {
        var grupo = grupoRepository.findByIdForUpdate(grupoId)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
        entityManager.refresh(grupo, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        escopoUnidadeService.validarGrupo(grupo);
        return grupo;
    }

    private enum FinalidadeBloqueioSessao { OPERACIONAL, CLINICA }

    private record ResponsabilidadeSessao(StatusSessaoGrupo status, UUID responsavel) {}

    private ResponsabilidadeSessao responsabilidadeEscalar(Long id) {
        Object[] dados = entityManager.createQuery(
                "select s.status, g.coordenador.idPublico, r.idPublico from SessaoGrupo s join s.grupo g left join s.responsavelAtendimento r where s.id=:id",
                Object[].class).setParameter("id", id).getResultList().stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Sessão não encontrada."));
        var status = (StatusSessaoGrupo) dados[0];
        return new ResponsabilidadeSessao(status, (UUID) (status == StatusSessaoGrupo.REALIZADA ? dados[2] : dados[1]));
    }

    private Set<UUID> responsaveisHistoricosEscalares(Long grupoId) {
        return new HashSet<>(entityManager.createQuery(
                "select distinct r.idPublico from SessaoGrupo s join s.responsavelAtendimento r where s.grupo.id=:id and s.status=:status",
                UUID.class).setParameter("id", grupoId).setParameter("status", StatusSessaoGrupo.REALIZADA).getResultList());
    }

    /**
     * Pacientes -> responsável vigente ou histórico -> grupo -> sessão.
     * Na confirmação, descobrir a coordenação após os locks de pacientes:
     * a transferência de grupo com participantes usa a mesma ordem.
     * Sem adquirir novo recurso depois do grupo; identidade divergente exige
     * autorização consolidada antes de eventual recusa segura para nova tentativa.
     */
    private SessaoGrupo bloquearSessao(Long id,List<UUID> additionalPatients,
            FinalidadeBloqueioSessao finalidade) {
        Long groupId=sessaoRepository.findGrupoId(id).orElseThrow(()->new ResourceNotFoundException("Sessão não encontrada."));
        Set<UUID> inscritos = new HashSet<>(participanteRepository.findPacienteIds(id));
        Set<UUID> lockedIds = new HashSet<>(inscritos);
        lockedIds.addAll(additionalPatients);
        ResponsabilidadeSessao descoberta = null;
        if (finalidade == FinalidadeBloqueioSessao.OPERACIONAL) {
            bloquearGrupoComSujeitos(groupId, lockedIds);
        } else {
            agendaAssistencialLocks.bloquear(lockedIds, List.of());
            descoberta = responsabilidadeEscalar(id);
            var responsaveis = descoberta.responsavel() == null ? List.<UUID>of() : List.of(descoberta.responsavel());
            if (descoberta.status() == StatusSessaoGrupo.REALIZADA)
                agendaAssistencialLocks.bloquearResponsaveisClinicos(List.of(), responsaveis);
            else
                agendaAssistencialLocks.bloquearResponsaveisClinicos(responsaveis, List.of());
            bloquearGrupoAposSujeitos(groupId);
        }
        if (!inscritos.equals(new HashSet<>(participanteRepository.findPacienteIds(id))))
            throw new ConflictException("A lista de participantes foi alterada; atualize a sessão antes de tentar novamente.");
        var sessao = sessaoRepository.findByIdForUpdate(id).orElseThrow(()->new ResourceNotFoundException("Sessão não encontrada."));
        if (!groupId.equals(sessao.getGrupo().getId()))
            throw new ConflictException("A estrutura da sessão foi alterada; atualize antes de tentar novamente.");
        escopoUnidadeService.validarSessao(sessao);
        if (descoberta != null && !descoberta.equals(responsabilidadeEscalar(id))) {
            autorizacaoClinica.validarSessao(sessao);
            throw new ConflictException("A responsabilidade da sessão foi alterada; atualize antes de tentar novamente.");
        }
        return sessao;
    }

    private PendenciaFrequenciaException conflitoFactualGrupo(SessaoGrupo sessao, String tipo) {
        return new PendenciaFrequenciaException("CONFLITO_ASSISTENCIAL_PENDENTE_RECONCILIACAO", tipo, null, sessao.getId());
    }

    void validarRealizacaoColetiva(SessaoGrupo sessao) {
        var quando = new IntegridadeAgendaAssistencialService.Instante(sessao.getDataSessao(), sessao.getHorario());
        var exclusao = new IntegridadeAgendaAssistencialService.Exclusao(null, sessao.getId());
        var avaliacao = integridadeAgendaAssistencial.avaliarProfissional(sessao.getGrupo().getCoordenador(), quando, exclusao);
        if (avaliacao.temImpedimentoOperacional())
            throw conflitoFactualGrupo(sessao, "SESSAO_PROFISSIONAL");
    }

    private void validarPresencaColetiva(Paciente paciente, SessaoGrupo sessao) {
        validarPresencaColetiva(paciente, sessao, null);
    }

    void validarPresencaColetiva(Paciente paciente, SessaoGrupo sessao, VinculoAssistencial local) {
        var quando = new IntegridadeAgendaAssistencialService.Instante(sessao.getDataSessao(), sessao.getHorario());
        var exclusao = new IntegridadeAgendaAssistencialService.Exclusao(null, sessao.getId());
        var avaliacao = local == null ? integridadeAgendaAssistencial.avaliarPaciente(paciente, quando, exclusao)
                : integridadeAgendaAssistencial.avaliarPaciente(local, quando, exclusao);
        if (avaliacao.temImpedimentoOperacional())
            throw conflitoFactualGrupo(sessao, "HISTORICO_PACIENTE");
        // FALTOU não representa presença física. Não resolver empates de indicadores aqui.
        // Correção/retroativa de sessão realizada não criam um novo fato do responsável.
    }

    void validarCompromissoCoordenador(Usuario coordenador, LocalDate data, LocalTime hora, Long sessaoId) {
        var instante = new IntegridadeAgendaAssistencialService.Instante(data, hora);
        var avaliacao = integridadeAgendaAssistencial.avaliarProfissional(coordenador, instante,
                new IntegridadeAgendaAssistencialService.Exclusao(null, sessaoId));
        if (avaliacao.temImpedimentoOperacional())
            throw new ConflictException("O profissional possui compromisso assistencial incompatível neste horário.");
    }

    private void validarNovaParticipacao(Paciente paciente, LocalDate data, LocalTime hora, Long sessaoId) {
        validarNovaParticipacao(paciente, data, hora, sessaoId, null);
    }

    void validarNovaParticipacao(Paciente paciente, LocalDate data, LocalTime hora, Long sessaoId,
            VinculoAssistencial local) {
        if ((local == null ? paciente.getStatusPaciente() : local.getStatusPaciente()) != StatusPaciente.ATIVO)
            throw new ValidationException("Somente pacientes ativos podem participar de grupos.");
        var instante = new IntegridadeAgendaAssistencialService.Instante(data, hora);
        var exclusao = new IntegridadeAgendaAssistencialService.Exclusao(null, sessaoId);
        var avaliacao = local == null ? integridadeAgendaAssistencial.avaliarPaciente(paciente, instante, exclusao)
                : integridadeAgendaAssistencial.avaliarPaciente(local, instante, exclusao);
        // Preserva a guarda coletiva antiga, inclusive fatos não conclusivos em sessão realizada.
        if (avaliacao.temImpedimentoOperacional()
                || avaliacao.tem(IntegridadeAgendaAssistencialService.Sinal.FALTA_GRUPO)
                || avaliacao.tem(IntegridadeAgendaAssistencialService.Sinal.FREQUENCIA_GRUPO_DESCONHECIDA))
            throw new ConflictException("O paciente possui compromisso assistencial incompatível neste horário.");
    }

    SessaoGrupo novaSessao(GrupoTerapeutico grupo, LocalDate data, LocalTime horario) {
        SessaoGrupo s = new SessaoGrupo();
        s.setGrupo(grupo);
        // Grupo legado sem origem permanece pendente, sem atribuição retroativa.
        if (grupo.getUnidadeOrigemChave() != null) s.registrarUnidadeDeOrigemDoGrupo();
        s.setDataSessao(data);
        s.setHorario(horario);
        s.setStatus(StatusSessaoGrupo.AGENDADA);
        s.designarProfissional(grupo.getCoordenador());
        return s;
    }

    private void adicionarParticipanteInterno(SessaoGrupo sessao, UUID pacienteId) {
        Paciente paciente = pacienteRepository.findByIdPublico(pacienteId).filter(escopoUnidadeService::pacientePertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado."));

        if (paciente.getStatusPaciente() != StatusPaciente.ATIVO) {
            throw new ValidationException("Somente pacientes ativos podem participar de grupos.");
        }

        if (participanteRepository.findBySessaoGrupoAndPaciente(sessao, paciente).isPresent())
            throw new ConflictException("Este paciente já está na lista de participantes desta sessão.");
        if (sessaoRepository.existsParticipacaoNoMesmoHorario(paciente, sessao.getDataSessao(), sessao.getHorario(),
                StatusSessaoGrupo.CANCELADA))
            throw new ConflictException("Já existe participação do paciente em outra sessão em "
                    + sessao.getDataSessao() + " às " + sessao.getHorario() + ".");
        SessaoGrupoParticipante p = new SessaoGrupoParticipante();
        p.setSessaoGrupo(sessao);
        p.setPaciente(paciente);
        p.setStatusPresenca(StatusPresencaGrupo.NAO_REGISTRADA);
        participanteRepository.save(p);
        sessao.getParticipantes().add(p);
    }

    /**
     * Operacoes em lote nao podem ignorar parte das sessoes e continuar
     * gravando as demais: a presenca de uma unica origem incoerente exige
     * reconciliacao humana e interrompe toda a transacao antes da escrita.
     * Sessoes historicas sem origem continuam sob a barreira legada.
     */
    private List<SessaoGrupo> sessoesParaOperacaoLocal(GrupoTerapeutico grupo) {
        List<SessaoGrupo> serie = sessaoRepository.findByGrupoOrderByDataSessaoDesc(grupo);
        serie.forEach(escopoUnidadeService::validarSessao);
        return serie;
    }

    void validarSessaoEditavel(SessaoGrupo sessao) {
        if (sessao.getStatus() != StatusSessaoGrupo.AGENDADA)
            throw new ValidationException(
                    "Não é possível alterar participantes de uma sessão já realizada ou cancelada.");
        if (!sessaoAindaNaoIniciou(sessao))
            throw new ValidationException("Não é possível alterar participantes após o início da sessão.");
    }

    boolean sessaoAindaNaoIniciou(SessaoGrupo sessao) {
        return LocalDateTime.of(sessao.getDataSessao(), sessao.getHorario()).isAfter(LocalDateTime.now(clock));
    }

    private SessaoGrupo buscarSessao(Long id) {
        return sessaoRepository.findById(id)
                .filter(escopoUnidadeService::sessaoPertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException("Sessão não encontrada."));
    }

    private SessaoGrupoParticipante buscarParticipante(SessaoGrupo s, UUID id) {
        Paciente p = pacienteRepository.findByIdPublico(id).filter(escopoUnidadeService::pacientePertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado."));
        return participanteRepository.findBySessaoGrupoAndPaciente(s, p)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente não é participante desta sessão."));
    }

    private List<UUID> idsOuVazios(List<UUID> ids) {
        return ids == null ? List.of() : ids;
    }

    private GrupoTerapeuticoDTO toGrupoDTO(GrupoTerapeutico g) {
        escopoUnidadeService.validarGrupo(g);
        List<SessaoGrupo> sessoes = sessaoRepository.findByGrupoOrderByDataSessaoDesc(g).stream()
                .filter(escopoUnidadeService::sessaoPertenceAoEscopo).toList();
        LocalDate primeira = sessoes.stream().map(SessaoGrupo::getDataSessao).min(LocalDate::compareTo).orElse(null);
        LocalDateTime agora = LocalDateTime.now(clock);
        boolean iniciado = sessoes.stream().anyMatch(s -> s.getStatus() != StatusSessaoGrupo.AGENDADA || !LocalDateTime.of(s.getDataSessao(), s.getHorario()).isAfter(agora));
        return new GrupoTerapeuticoDTO(g.getId(), g.getTema(), g.getCoordenador().getIdPublico(),
                g.getCoordenador().getNome(), g.getRecorrencia(), g.getHorarioPadrao(),
                g.getDataFimRecorrencia(), g.isAtivo(), primeira, g.getVersion(), iniciado);
    }

    private SessaoGrupoDTO toSessaoDTO(SessaoGrupo s) {
        escopoUnidadeService.validarSessao(s);
        List<ParticipanteSessaoDTO> ps = s.getParticipantes().stream()
                .filter(p -> escopoUnidadeService.pacientePertenceAoEscopo(p.getPaciente()))
                .map(p -> new ParticipanteSessaoDTO(p.getPaciente().getIdPublico(), p.getPaciente().getNome(),
                        p.getStatusPresenca()))
                .toList();
        int presencas = s.getStatus() == StatusSessaoGrupo.REALIZADA
                ? (int) ps.stream().filter(p -> p.statusPresenca() == StatusPresencaGrupo.PRESENTE).count()
                : 0;
        return new SessaoGrupoDTO(s.getId(), s.getGrupo().getId(), s.getGrupo().getTema(),
                s.getGrupo().getCoordenador().getNome(), s.getDataSessao(), s.getHorario(), s.getStatus(),
                statusExibicao(s), s.getMotivoCancelamento(), ps, ps.size(), presencas, s.getVersion(),
                s.getResponsavelAtendimento()==null?null:s.getResponsavelAtendimento().getIdPublico(),
                s.getResponsavelAtendimento()==null?null:s.getResponsavelAtendimento().getNome(),
                s.isResponsabilidadePendente(),s.getMotivoResponsabilidadePendente(),s.getOrigemResponsabilidade(),
                s.getProfissionalDesignado()==null?null:s.getProfissionalDesignado().getIdPublico(),
                s.getProfissionalDesignado()==null?null:s.getProfissionalDesignado().getNome());
    }

    private StatusExibicaoSessaoGrupo statusExibicao(SessaoGrupo s) {
        return switch (s.getStatus()) {
            case REALIZADA -> StatusExibicaoSessaoGrupo.REALIZADO;
            case CANCELADA -> StatusExibicaoSessaoGrupo.CANCELADO;
            case AGENDADA ->
                sessaoAindaNaoIniciou(s) ? StatusExibicaoSessaoGrupo.AGENDADO : StatusExibicaoSessaoGrupo.EM_ANDAMENTO;
        };
    }
}
