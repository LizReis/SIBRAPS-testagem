package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.repositories.UsuarioRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Projeção experimental de DTOs existentes; nenhuma rota nova. */
@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
public class DtosAssistenciaisLocaisEnsaioService {
    private final EntityManager em;
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final GrupoTerapeuticoServiceImpl guardas;
    private final AgendamentoServiceImpl oferta;
    private final UsuarioRepository usuarios;
    private final Clock clock;

    @Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
    public AgendamentoDTO atendimento(Long id) {
        var contexto = atores.autorizarOperacional();
        var a = em.createQuery("select a from Agendamento a where a.id=:id and a.unidadeOrigemChave=:unidade",Agendamento.class)
                .setParameter("id",id).setParameter("unidade",contexto.unidade()).getResultList().stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Atendimento não encontrado."));
        autorizacao.validarAgendamentoLocal(a);
        var v = autorizacao.exigirVinculoLocal(a.getPaciente().getIdPublico());
        if (!ProjecaoClinicaVinculoService.chaveCanonica(v)) throw new ResourceNotFoundException("Atendimento não encontrado.");
        return new AgendamentoDTO(a.getId(),a.getUsuario().getIdPublico(),a.getUsuario().getNome(),
                a.getPaciente().getIdPublico(),a.getPaciente().getNome(),v.getTipoAcompanhamento(),
                a.getDataAgendamento(),a.getTurnoAgendamento(),a.getHoraAtendimento(),a.getSituacaoAtendimento(),
                a.getAgendamentoOriginal()==null?null:a.getAgendamentoOriginal().getId(),a.getVersion(),
                a.getSituacaoReserva(),a.getFrequenciaAssistencial(),a.isMigracaoPendente(),a.getMotivoMigracaoPendente(),a.getOcupaVaga());
    }

    @Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
    public SessaoGrupoDTO sessao(Long id) {
        var contexto = atores.autorizarOperacional();
        var s = em.createQuery("""
                select s from SessaoGrupo s where s.id=:id and s.unidadeOrigemChave=:unidade
                and s.grupo.unidadeOrigemChave=:unidade
                """,SessaoGrupo.class).setParameter("id",id).setParameter("unidade",contexto.unidade())
                .getResultList().stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Sessão não encontrada."));
        if (AvaliacaoProvenienciaClinica.origemSessao(s)!=AvaliacaoProvenienciaClinica.Situacao.COMPROVADA)
            throw new ConflictException("Sessão pendente de conferência.");
        // Falhar fechado em composição inconsistente, não mostrar lote parcial.
        List<ParticipanteSessaoDTO> ps = s.getParticipantes().stream().map(p -> {
            autorizacao.validarSessaoLocal(s,p.getPaciente());
            var v = autorizacao.exigirVinculoLocal(p.getPaciente().getIdPublico());
            if (!ProjecaoClinicaVinculoService.chaveCanonica(v)) throw new ResourceNotFoundException("Sessão não encontrada.");
            return new ParticipanteSessaoDTO(p.getPaciente().getIdPublico(),p.getPaciente().getNome(),p.getStatusPresenca());
        }).toList();
        var status = switch (s.getStatus()) {
            case REALIZADA -> StatusExibicaoSessaoGrupo.REALIZADO;
            case CANCELADA -> StatusExibicaoSessaoGrupo.CANCELADO;
            case AGENDADA -> guardas.sessaoAindaNaoIniciou(s)?StatusExibicaoSessaoGrupo.AGENDADO:StatusExibicaoSessaoGrupo.EM_ANDAMENTO;
        };
        int presencas=(int)ps.stream().filter(p -> p.statusPresenca()==StatusPresencaGrupo.PRESENTE).count();
        return new SessaoGrupoDTO(s.getId(),s.getGrupo().getId(),s.getGrupo().getTema(),s.getGrupo().getCoordenador().getNome(),
                s.getDataSessao(),s.getHorario(),s.getStatus(),status,s.getMotivoCancelamento(),ps,ps.size(),presencas,s.getVersion(),
                s.getResponsavelAtendimento()==null?null:s.getResponsavelAtendimento().getIdPublico(),
                s.getResponsavelAtendimento()==null?null:s.getResponsavelAtendimento().getNome(),
                s.isResponsabilidadePendente(),s.getMotivoResponsabilidadePendente(),s.getOrigemResponsabilidade());
    }

    public List<AgendamentoDTO> agenda(LocalDate inicio, LocalDate fim, UUID profissional) {
        validarPeriodo(inicio, fim);
        var contexto = atores.autorizarOperacional();
        if (contexto.perfil() == TipoUsuario.PROFISSIONAL) {
            if (profissional != null && !profissional.equals(contexto.ator()))
                throw new ValidationException("Profissional não pode consultar a agenda de outro profissional.");
            profissional = contexto.ator();
        }
        if (profissional != null) exigirProfissional(profissional, contexto.unidade());
        return em.createQuery("""
                select a.id from Agendamento a where a.unidadeOrigemChave=:unidade
                and a.dataAgendamento between :inicio and :fim
                and (:profissional is null or a.usuario.idPublico=:profissional)
                order by a.dataAgendamento, a.horaAtendimento, a.id
                """, Long.class).setParameter("unidade", contexto.unidade()).setParameter("inicio", inicio)
                .setParameter("fim", fim).setParameter("profissional", profissional).getResultList().stream()
                .map(this::atendimento).toList();
    }

    public List<LocalDate> datasRemarcacao(Long id) {
        var contexto = atores.autorizarOperacional();
        if (contexto.perfil() == TipoUsuario.PROFISSIONAL)
            throw new org.springframework.security.access.AccessDeniedException("Perfil sem permissão para remarcar.");
        var a = atendimento(id);
        if (a.tipoAcompanhamento() == null || a.tipoAcompanhamento() == TipoAcompanhamento.GRUPO_TERAPEUTICO)
            throw new ConflictException("Modalidade individual não confirmada.");
        var profissional = exigirProfissional(a.usuarioId(), contexto.unidade());
        return oferta.buscarProximasVagasDisponiveis(profissional, a.turnoAgendamento(), LocalDate.now(clock), 3);
    }

    public HorariosDisponiveisDTO horarios(UUID profissional, LocalDate data, TurnoEnum turno) {
        exigirProfissional(profissional, atores.autorizarOperacional().unidade());
        return oferta.buscarHorariosDisponiveis(profissional, data, turno);
    }

    public java.util.Map<TurnoEnum, Integer> vagas(UUID profissional, LocalDate data) {
        exigirProfissional(profissional, atores.autorizarOperacional().unidade());
        return oferta.calcularVagasDisponiveis(profissional, data);
    }

    private Usuario exigirProfissional(UUID id, String unidade) {
        return usuarios.findByIdPublico(id).filter(u -> AtoresEstadoClinicoLocalEnsaio.elegivel(u, unidade, false))
                .orElseThrow(() -> new ResourceNotFoundException("Profissional não encontrado."));
    }

    public List<GrupoTerapeuticoDTO> grupos() {
        var unidade = atores.autorizarOperacional().unidade();
        return em.createQuery("select g from GrupoTerapeutico g where g.unidadeOrigemChave=:unidade order by g.tema,g.id",
                GrupoTerapeutico.class).setParameter("unidade", unidade).getResultList().stream().map(this::projetarGrupo).toList();
    }

    public GrupoTerapeuticoDTO grupo(Long id) {
        return projetarGrupo(exigirGrupo(id));
    }

    public List<SessaoGrupoDTO> sessoes(LocalDate inicio, LocalDate fim) {
        validarPeriodo(inicio, fim);
        return em.createQuery("""
                select s.id from SessaoGrupo s where s.unidadeOrigemChave=:unidade and s.grupo.unidadeOrigemChave=:unidade
                and s.dataSessao between :inicio and :fim order by s.dataSessao,s.horario,s.id
                """, Long.class).setParameter("unidade", atores.autorizarOperacional().unidade())
                .setParameter("inicio", inicio).setParameter("fim", fim).getResultList().stream().map(this::sessao).toList();
    }

    public LocalDate proximaData(Long id) {
        var grupo = exigirGrupo(id);
        var base = sessoesDoGrupo(grupo).stream().map(SessaoGrupo::getDataSessao).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElseGet(() -> LocalDate.now(clock));
        return switch (grupo.getRecorrencia()) {
            case SEMANAL -> base.plusWeeks(1);
            case QUINZENAL -> base.plusWeeks(2);
            case MENSAL -> base.plusMonths(1);
            case UNICA -> base;
        };
    }

    public List<SessaoInscricaoRetroativaDTO> retroativas(Long id) {
        atores.autorizar(false);
        return sessoesDoGrupo(exigirGrupo(id)).stream().map(s -> sessao(s.getId())).map(s ->
                new SessaoInscricaoRetroativaDTO(s.id(), s.dataSessao(), s.horario(), s.status(), s.statusExibicao(),
                        s.status() == StatusSessaoGrupo.REALIZADA)).toList();
    }

    private record Participacao(SessaoGrupoDTO sessao, ParticipanteSessaoDTO pessoa) {}

    public List<ParticipanteGrupoDTO> participantesGrupo(Long id) {
        var agora = LocalDateTime.now(clock);
        return sessoesDoGrupo(exigirGrupo(id)).stream().map(s -> sessao(s.getId()))
                .flatMap(s -> s.participantes().stream().map(p -> new Participacao(s, p)))
                .collect(Collectors.groupingBy(p -> p.pessoa().pacienteId())).values().stream().map(participacoes -> {
                    var pessoa = participacoes.getFirst().pessoa();
                    var finais = participacoes.stream().filter(p -> p.sessao().status() == StatusSessaoGrupo.REALIZADA
                            && AvaliacaoProvenienciaClinica.frequenciaRegistrada(p.pessoa().statusPresenca())).toList();
                    long presencas = finais.stream().filter(p -> p.pessoa().statusPresenca() == StatusPresencaGrupo.PRESENTE).count();
                    var desde = participacoes.stream().map(p -> p.sessao().dataSessao()).min(Comparator.naturalOrder()).orElseThrow();
                    boolean futura = participacoes.stream().map(Participacao::sessao)
                            .anyMatch(s -> s.status() == StatusSessaoGrupo.AGENDADA
                                    && LocalDateTime.of(s.dataSessao(), s.horario()).isAfter(agora));
                    return new ParticipanteGrupoDTO(pessoa.pacienteId(), pessoa.nomePaciente(), desde, finais.size(),
                            presencas, finais.size() - presencas, finais.isEmpty() ? null : presencas * 100.0 / finais.size(), futura);
                }).sorted(Comparator.comparing(ParticipanteGrupoDTO::nomePaciente)).toList();
    }

    private GrupoTerapeutico exigirGrupo(Long id) {
        return em.createQuery("select g from GrupoTerapeutico g where g.id=:id and g.unidadeOrigemChave=:unidade",
                GrupoTerapeutico.class).setParameter("id", id).setParameter("unidade", atores.autorizarOperacional().unidade())
                .getResultStream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
    }

    private List<SessaoGrupo> sessoesDoGrupo(GrupoTerapeutico grupo) {
        return em.createQuery("""
                select s from SessaoGrupo s where s.grupo=:grupo and s.unidadeOrigemChave=:unidade
                order by s.dataSessao desc,s.horario desc,s.id desc
                """, SessaoGrupo.class).setParameter("grupo", grupo)
                .setParameter("unidade", grupo.getUnidadeOrigemChave()).getResultList();
    }

    private GrupoTerapeuticoDTO projetarGrupo(GrupoTerapeutico g) {
        var atuais = sessoesDoGrupo(g);
        var primeira = atuais.stream().map(SessaoGrupo::getDataSessao).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        return new GrupoTerapeuticoDTO(g.getId(), g.getTema(), g.getCoordenador().getIdPublico(),
                g.getCoordenador().getNome(), g.getRecorrencia(), g.getHorarioPadrao(), g.getDataFimRecorrencia(),
                g.isAtivo(), primeira, g.getVersion(), atuais.stream().anyMatch(s -> !guardas.sessaoAindaNaoIniciou(s)));
    }

    private static void validarPeriodo(LocalDate inicio, LocalDate fim) {
        if (inicio.isAfter(fim) || inicio.plusDays(62).isBefore(fim))
            throw new ValidationException("Informe um intervalo válido de até 62 dias.");
    }
}
