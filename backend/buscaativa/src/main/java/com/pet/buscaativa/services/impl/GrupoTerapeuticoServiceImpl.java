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

    private final GrupoTerapeuticoRepository grupoRepository;
    private final SessaoGrupoRepository sessaoRepository;
    private final SessaoGrupoParticipanteRepository participanteRepository;
    private final UsuarioRepository usuarioRepository;
    private final PacienteRepository pacienteRepository;
    private final PacienteService pacienteService;
    private final HistoricoPacienteService historicoPacienteService;
    private final InscricaoRetroativaGrupoAuditoriaRepository auditoriaRetroativaRepository;
    private final AgendamentoRepository agendamentoRepository;
    private final Clock clock;
    private final EscopoUnidadeService escopoUnidadeService;

    @Override
    @Transactional
    public GrupoTerapeuticoDTO criarGrupo(CriarGrupoDTO dto) {
        validarPeriodoRecorrencia(dto);
        Usuario coordenador = usuarioRepository.findByIdPublico(dto.coordenadorId())
                .orElseThrow(() -> new ResourceNotFoundException("Coordenador não encontrado."));

        escopoUnidadeService.validarUsuario(coordenador);
        if (coordenador.getTipoUsuario() != TipoUsuario.PROFISSIONAL
                && coordenador.getTipoUsuario() != TipoUsuario.ADMINISTRADOR) {
            throw new ValidationException("O coordenador do grupo deve ser um profissional ou administrador.");
        }

        if (dto.dataPrimeiraSessao().isBefore(LocalDate.now(clock))) {
            throw new ValidationException("Não é permitido criar um grupo com a primeira sessão em data passada.");
        }
        validarIdsParticipantes(dto.participantesIds());

        GrupoTerapeutico grupo = new GrupoTerapeutico();
        grupo.setTema(dto.tema());
        grupo.setCoordenador(coordenador);
        grupo.setRecorrencia(dto.recorrencia());
        grupo.setHorarioPadrao(dto.horario());
        grupo.setDataFimRecorrencia(dto.dataFimRecorrencia());
        grupo.setAtivo(true);

        grupo = grupoRepository.save(grupo);

        for (LocalDate data : gerarDatasDaSerie(dto.dataPrimeiraSessao(), dto.dataFimRecorrencia(),
                dto.recorrencia())) {
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

    private void validarPeriodoRecorrencia(CriarGrupoDTO dto) {
        if (dto.recorrencia() != RecorrenciaGrupo.UNICA && dto.dataFimRecorrencia() == null) {
            throw new ValidationException("A data final da recorrência é obrigatória para grupos recorrentes.");
        }
        if (dto.dataFimRecorrencia() != null && dto.dataFimRecorrencia().isBefore(dto.dataPrimeiraSessao())) {
            throw new ValidationException("A data final da recorrência não pode ser anterior à primeira sessão.");
        }
    }

    private void validarIdsParticipantes(List<UUID> ids) {
        if (ids != null && new HashSet<>(ids).size() != ids.size()) {
            throw new ConflictException("A lista inicial contém paciente duplicado.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<GrupoTerapeuticoDTO> listarGrupos() {
        return grupoRepository.findByAtivoTrue().stream().filter(escopoUnidadeService::grupoPertenceAoEscopo).map(this::toGrupoDTO).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GrupoTerapeuticoDTO buscarGrupo(Long grupoId) {
        return toGrupoDTO(grupoRepository.findById(grupoId).filter(escopoUnidadeService::grupoPertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado.")));
    }

    @Override
    @Transactional
    public GrupoTerapeuticoDTO atualizarGrupo(Long grupoId, AtualizarGrupoTerapeuticoDTO dto) {
        GrupoTerapeutico grupo = grupoRepository.findById(grupoId).filter(escopoUnidadeService::grupoPertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
        if (!dto.version().equals(grupo.getVersion()))
            throw new OptimisticLockException("O grupo foi alterado por outro usuário.");
        Usuario coordenador = usuarioRepository.findByIdPublico(dto.coordenadorId())
                .orElseThrow(() -> new ResourceNotFoundException("Coordenador não encontrado."));
        escopoUnidadeService.validarUsuario(coordenador);
        if (coordenador.getTipoUsuario() != TipoUsuario.PROFISSIONAL && coordenador.getTipoUsuario() != TipoUsuario.ADMINISTRADOR)
            throw new ValidationException("O coordenador deve ser profissional ou administrador.");
        List<SessaoGrupo> sessoes = sessaoRepository.findByGrupoOrderByDataSessaoDesc(grupo);
        LocalDateTime agora = LocalDateTime.now(clock);
        boolean iniciado = sessoes.stream().anyMatch(s -> s.getStatus() != StatusSessaoGrupo.AGENDADA
                || !LocalDateTime.of(s.getDataSessao(), s.getHorario()).isAfter(agora));
        SessaoGrupo primeira = sessoes.stream().min(Comparator.comparing(SessaoGrupo::getDataSessao).thenComparing(SessaoGrupo::getHorario))
                .orElseThrow(() -> new ValidationException("Grupo sem sessões."));
        boolean estrutural = !primeira.getDataSessao().equals(dto.dataPrimeiraSessao())
                || !grupo.getHorarioPadrao().equals(dto.horario()) || grupo.getRecorrencia() != dto.recorrencia()
                || !java.util.Objects.equals(grupo.getDataFimRecorrencia(), dto.dataFimRecorrencia());
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
            sessaoRepository.deleteAll(sessoes);
            sessaoRepository.flush();
            for (LocalDate data : gerarDatasDaSerie(dto.dataPrimeiraSessao(), dto.dataFimRecorrencia(), dto.recorrencia())) {
                SessaoGrupo nova = sessaoRepository.save(novaSessao(grupo, data, dto.horario()));
                for (UUID pacienteId : participantes) adicionarParticipanteInterno(nova, pacienteId);
            }
        }
        grupo.setTema(dto.tema().trim()); grupo.setCoordenador(coordenador); grupo.setHorarioPadrao(dto.horario());
        grupo.setRecorrencia(dto.recorrencia()); grupo.setDataFimRecorrencia(dto.dataFimRecorrencia());
        return toGrupoDTO(grupoRepository.saveAndFlush(grupo));
    }

    @Override
    @Transactional(readOnly = true)
    public LocalDate sugerirProximaData(Long grupoId) {
        GrupoTerapeutico grupo = grupoRepository.findById(grupoId).filter(escopoUnidadeService::grupoPertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));

        LocalDate base = sessaoRepository.findFirstByGrupoOrderByDataSessaoDesc(grupo)
                .map(SessaoGrupo::getDataSessao).orElse(LocalDate.now(clock));

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
        GrupoTerapeutico grupo = grupoRepository.findById(dto.grupoId())
                .filter(escopoUnidadeService::grupoPertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));

        if (!grupo.isAtivo())
            throw new ValidationException("Não é possível criar sessões para um grupo inativo.");
        if (dto.dataSessao().isBefore(LocalDate.now(clock)))
            throw new ValidationException("Não é permitido criar sessão em data passada.");
        boolean existe = sessaoRepository.findByGrupoOrderByDataSessaoDesc(grupo).stream()
                .anyMatch(s -> s.getDataSessao().equals(dto.dataSessao())
                        && s.getStatus() != StatusSessaoGrupo.CANCELADA);
        if (existe)
            throw new ConflictException("Já existe uma sessão agendada para este grupo nesta data.");
        validarIdsParticipantes(dto.participantesIds());
        SessaoGrupo sessao = sessaoRepository.save(novaSessao(grupo, dto.dataSessao(),
                dto.horario() == null ? grupo.getHorarioPadrao() : dto.horario()));
        for (UUID id : idsOuVazios(dto.participantesIds()))
            adicionarParticipanteInterno(sessao, id);

        return toSessaoDTO(sessao);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessaoGrupoDTO> listarSessoes(LocalDate inicio, LocalDate fim) {
        if (inicio.isAfter(fim))
            throw new ValidationException("A data inicial não pode ser posterior à data final.");
        return sessaoRepository.findByDataSessaoBetween(inicio, fim).stream()
                .filter(s -> escopoUnidadeService.grupoPertenceAoEscopo(s.getGrupo())).map(this::toSessaoDTO).toList();
    }

    @Override
    @Transactional
    public SessaoGrupoDTO adicionarParticipante(Long sessaoId, AdicionarParticipanteDTO dto) {
        SessaoGrupo sessao = buscarSessao(sessaoId);

        validarSessaoEditavel(sessao);

        adicionarParticipanteInterno(sessao, dto.pacienteId());

        return toSessaoDTO(sessao);
    }

    @Override
    @Transactional
    public SessaoGrupoDTO removerParticipante(Long sessaoId, UUID pacienteId) {
        SessaoGrupo sessao = buscarSessao(sessaoId);

        validarSessaoEditavel(sessao);

        SessaoGrupoParticipante participante = buscarParticipante(sessao, pacienteId);

        sessao.getParticipantes().remove(participante);
        participanteRepository.delete(participante);

        return toSessaoDTO(sessao);
    }

    @Override
    @Transactional
    public SessaoGrupoDTO registrarPresenca(Long sessaoId, UUID pacienteId, RegistrarPresencaGrupoDTO dto) {
        SessaoGrupo sessao = buscarSessao(sessaoId);
        if (sessao.getStatus() != StatusSessaoGrupo.AGENDADA)
            throw new ValidationException("A frequência provisória só pode ser alterada em sessão agendada.");
        if (sessaoAindaNaoIniciou(sessao))
            throw new ValidationException("Não é possível registrar presença antes do início da sessão.");
        SessaoGrupoParticipante participante = buscarParticipante(sessao, pacienteId);
        participante.setStatusPresenca(dto.statusPresenca());
        participanteRepository.save(participante);
        return toSessaoDTO(sessao);
    }

    @Override
    @Transactional
    public SessaoGrupoDTO atualizarStatus(Long sessaoId, StatusSessaoGrupo novoStatus, Integer expectedVersion) {
        SessaoGrupo sessao = buscarSessao(sessaoId);
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
        SessaoGrupo sessao = sessaoRepository.findByIdForUpdate(sessaoId)
                .orElseThrow(() -> new ResourceNotFoundException("Sessão não encontrada."));
        escopoUnidadeService.validarSessaoCompleta(sessao);
        if (sessao.getStatus() != StatusSessaoGrupo.AGENDADA)
            throw new ConflictException("Sessão já foi confirmada como " + sessao.getStatus() + ".");
        if (dto.version() != null && !dto.version().equals(sessao.getVersion()))
            throw new OptimisticLockException("A sessão foi alterada por outro usuário.");
        if (!dto.ocorreu()) {
            for (var p : sessao.getParticipantes())
                p.setStatusPresenca(StatusPresencaGrupo.NAO_REGISTRADA);
            participanteRepository.saveAll(sessao.getParticipantes());
            sessao.setMotivoCancelamento(dto.motivoCancelamento());
            sessao.setStatus(StatusSessaoGrupo.CANCELADA);
            return toSessaoDTO(sessaoRepository.save(sessao));
        }
        if (sessaoAindaNaoIniciou(sessao))
            throw new ValidationException("Não é possível confirmar uma sessão futura.");
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
        List<Paciente> pacientes = new ArrayList<>();

        for (var p : participantes) {
            Paciente bloqueado = pacienteRepository.findByIdPublicoForUpdate(p.getPaciente().getIdPublico())
                    .orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado."));
            escopoUnidadeService.validarAcesso(bloqueado);
            p.setPaciente(bloqueado);
            p.setStatusPresenca(mapa.getOrDefault(bloqueado.getIdPublico(), StatusPresencaGrupo.FALTOU));
            pacientes.add(bloqueado);
        }
        participanteRepository.saveAll(participantes);
        sessao.setMotivoCancelamento(null);
        sessao.setStatus(StatusSessaoGrupo.REALIZADA);
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
        GrupoTerapeutico grupo = grupoRepository.findById(grupoId).filter(escopoUnidadeService::grupoPertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
        Paciente paciente = pacienteRepository.findByIdPublicoForUpdate(dto.pacienteId()).filter(escopoUnidadeService::pacientePertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado."));
        if (paciente.getStatusPaciente() != StatusPaciente.ATIVO)
            throw new ValidationException("Somente pacientes ativos podem ser inscritos.");
        List<SessaoGrupo> sessoes = sessaoRepository.findByGrupoOrderByDataSessaoDesc(grupo);
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
        GrupoTerapeutico g = grupoRepository.findById(grupoId).filter(escopoUnidadeService::grupoPertenceAoEscopo).orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
        return sessaoRepository.findByGrupoOrderByDataSessaoDesc(g).stream().map(s -> new SessaoInscricaoRetroativaDTO(
                s.getId(), s.getDataSessao(), s.getHorario(), s.getStatus(), statusExibicao(s), s.getStatus() == StatusSessaoGrupo.REALIZADA)).toList();
    }

    @Override
    @Transactional
    public SessaoGrupoDTO inscreverEmSessoesFuturas(Long grupoId, InscricaoFuturaGrupoDTO dto) {
        GrupoTerapeutico grupo = grupoRepository.findById(grupoId).filter(escopoUnidadeService::grupoPertenceAoEscopo).orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
        Paciente paciente = pacienteRepository.findByIdPublicoForUpdate(dto.pacienteId()).filter(escopoUnidadeService::pacientePertenceAoEscopo).orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado."));
        if (paciente.getStatusPaciente() != StatusPaciente.ATIVO) throw new ValidationException("Somente pacientes ativos podem ser inscritos.");
        LocalDateTime agora = LocalDateTime.now(clock);
        List<SessaoGrupo> futuras = sessaoRepository.findByGrupoOrderByDataSessaoDesc(grupo).stream()
                .filter(s -> s.getStatus() == StatusSessaoGrupo.AGENDADA && LocalDateTime.of(s.getDataSessao(), s.getHorario()).isAfter(agora))
                .sorted(Comparator.comparing(SessaoGrupo::getDataSessao).thenComparing(SessaoGrupo::getHorario)).toList();
        if (futuras.isEmpty()) throw new ValidationException("Não existem sessões futuras para inscrição.");
        for (SessaoGrupo s : futuras) {
            if (participanteRepository.findBySessaoGrupoAndPaciente(s, paciente).isEmpty()
                    && sessaoRepository.existsParticipacaoNoMesmoHorario(paciente, s.getDataSessao(), s.getHorario(), StatusSessaoGrupo.CANCELADA))
                throw new ConflictException("Conflito para " + paciente.getNome() + " em " + s.getDataSessao() + " às " + s.getHorario() + ".");
            if (agendamentoRepository.existsAtivoDoPacienteNoMesmoHorario(paciente, s.getDataSessao(), s.getHorario(),
                    List.of(SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO)))
                throw new ConflictException("Conflito para " + paciente.getNome() + " em " + s.getDataSessao() + " às " + s.getHorario() + ".");
        }
        for (SessaoGrupo s : futuras) if (participanteRepository.findBySessaoGrupoAndPaciente(s, paciente).isEmpty())
            adicionarParticipanteInterno(s, paciente.getIdPublico());
        return toSessaoDTO(futuras.get(0));
    }

    @Override
    @Transactional
    public void removerParticipanteDoGrupo(Long grupoId, UUID pacienteId) {
        GrupoTerapeutico grupo = grupoRepository.findById(grupoId).filter(escopoUnidadeService::grupoPertenceAoEscopo).orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
        Paciente paciente = pacienteRepository.findByIdPublico(pacienteId).filter(escopoUnidadeService::pacientePertenceAoEscopo).orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado."));
        LocalDateTime agora = LocalDateTime.now(clock);
        List<SessaoGrupoParticipante> remover = sessaoRepository.findByGrupoOrderByDataSessaoDesc(grupo).stream()
                .filter(s -> s.getStatus() == StatusSessaoGrupo.AGENDADA && LocalDateTime.of(s.getDataSessao(), s.getHorario()).isAfter(agora))
                .map(s -> participanteRepository.findBySessaoGrupoAndPaciente(s, paciente).orElse(null)).filter(java.util.Objects::nonNull).toList();
        if (remover.isEmpty()) throw new ValidationException("Não existem inscrições futuras para remover.");
        for (var p : remover) p.getSessaoGrupo().getParticipantes().remove(p);
        participanteRepository.deleteAll(remover);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ParticipanteGrupoDTO> listarParticipantesDoGrupo(Long grupoId) {
        GrupoTerapeutico grupo = grupoRepository.findById(grupoId).filter(escopoUnidadeService::grupoPertenceAoEscopo).orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
        LocalDateTime agora = LocalDateTime.now(clock);
        return sessaoRepository.findByGrupoOrderByDataSessaoDesc(grupo).stream().flatMap(s -> s.getParticipantes().stream())
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
        SessaoGrupo sessao = sessaoRepository.findByIdForUpdate(sessaoId).orElseThrow(() -> new ResourceNotFoundException("Sessão não encontrada."));
        escopoUnidadeService.validarGrupo(sessao.getGrupo());
        if (sessao.getStatus() != StatusSessaoGrupo.REALIZADA) throw new ValidationException("Somente sessões realizadas permitem correção de frequência.");
        if (dto.version() != null && !dto.version().equals(sessao.getVersion())) throw new OptimisticLockException("A sessão foi alterada por outro usuário.");
        Set<UUID> ids = new HashSet<>(); List<SessaoGrupoParticipante> alterados = new ArrayList<>();
        for (var f : dto.frequencias()) {
            if (!ids.add(f.pacienteId())) throw new ConflictException("Paciente duplicado nas correções.");
            if (f.statusPresenca() != StatusPresencaGrupo.PRESENTE && f.statusPresenca() != StatusPresencaGrupo.FALTOU)
                throw new ValidationException("A correção aceita somente PRESENTE ou FALTOU.");
            SessaoGrupoParticipante p = buscarParticipante(sessao, f.pacienteId()); p.setStatusPresenca(f.statusPresenca()); alterados.add(p);
        }
        participanteRepository.saveAll(alterados); sessaoRepository.saveAndFlush(sessao);
        for (var p : alterados) { pacienteService.recalcularAssiduidadePaciente(p.getPaciente()); historicoPacienteService.corrigirFrequenciaGrupo(p.getPaciente(), sessao, p.getStatusPresenca()); }
        return toSessaoDTO(sessao);
    }

    private SessaoGrupo novaSessao(GrupoTerapeutico grupo, LocalDate data, LocalTime horario) {
        SessaoGrupo s = new SessaoGrupo();
        s.setGrupo(grupo);
        s.setDataSessao(data);
        s.setHorario(horario);
        s.setStatus(StatusSessaoGrupo.AGENDADA);
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
            throw new ConflictException("Conflito para o paciente " + paciente.getNome() + " em "
                    + sessao.getDataSessao() + " às " + sessao.getHorario() + ".");
        SessaoGrupoParticipante p = new SessaoGrupoParticipante();
        p.setSessaoGrupo(sessao);
        p.setPaciente(paciente);
        p.setStatusPresenca(StatusPresencaGrupo.NAO_REGISTRADA);
        participanteRepository.save(p);
        sessao.getParticipantes().add(p);
    }

    private void validarSessaoEditavel(SessaoGrupo sessao) {
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
                .filter(s -> escopoUnidadeService.grupoPertenceAoEscopo(s.getGrupo()))
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
        List<SessaoGrupo> sessoes = sessaoRepository.findByGrupoOrderByDataSessaoDesc(g);
        LocalDate primeira = sessoes.stream().map(SessaoGrupo::getDataSessao).min(LocalDate::compareTo).orElse(null);
        LocalDateTime agora = LocalDateTime.now(clock);
        boolean iniciado = sessoes.stream().anyMatch(s -> s.getStatus() != StatusSessaoGrupo.AGENDADA || !LocalDateTime.of(s.getDataSessao(), s.getHorario()).isAfter(agora));
        return new GrupoTerapeuticoDTO(g.getId(), g.getTema(), g.getCoordenador().getIdPublico(),
                g.getCoordenador().getNome(), g.getRecorrencia(), g.getHorarioPadrao(),
                g.getDataFimRecorrencia(), g.isAtivo(), primeira, g.getVersion(), iniciado);
    }

    private SessaoGrupoDTO toSessaoDTO(SessaoGrupo s) {
        escopoUnidadeService.validarGrupo(s.getGrupo());
        List<ParticipanteSessaoDTO> ps = s.getParticipantes().stream()
                .filter(p -> escopoUnidadeService.pacientePertenceAoEscopo(p.getPaciente()))
                .map(p -> new ParticipanteSessaoDTO(p.getPaciente().getIdPublico(), p.getPaciente().getNome(),
                        p.getStatusPresenca()))
                .toList();
        int presencas = (int) ps.stream()
                .filter(p -> p.statusPresenca() == StatusPresencaGrupo.PRESENTE).count();
        return new SessaoGrupoDTO(s.getId(), s.getGrupo().getId(), s.getGrupo().getTema(),
                s.getGrupo().getCoordenador().getNome(), s.getDataSessao(), s.getHorario(), s.getStatus(),
                statusExibicao(s), s.getMotivoCancelamento(), ps, ps.size(), presencas, s.getVersion());
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
