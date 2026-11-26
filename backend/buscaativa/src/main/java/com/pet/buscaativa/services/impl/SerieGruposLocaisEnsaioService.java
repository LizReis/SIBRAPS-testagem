package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.migration.PlanoCoordenacaoEscritaClinica.Descoberta;
import com.pet.buscaativa.services.PoliticaTemporalFrequencia;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.CriarGrupoDTO;
import com.pet.buscaativa.entities.dto.NovaSessaoDTO;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.services.exceptions.*;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Ensaios de criação de grupos e sessões, sem usar estado clínico global. */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
public class SerieGruposLocaisEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final GrupoTerapeuticoServiceImpl guardas;
    private final EvidenciaStatusVinculo evidencia;
    private final EntityManager em;
    private final Clock clock;
    private final HistoricoFrequenciaLocalEnsaio historico;
    private final HistoricoPacienteRepository historicos;
    private final PersistenciaIndicadoresVinculoEnsaioService indicadores;
    private final PoliticaTemporalFrequencia temporal;
    private final DesignacaoSessaoService designacoes;

    public record Resultado(Long grupo, Integer versaoGrupo, List<Long> sessoes) {}
    public record Proxima(Long sessao, Integer versaoSessao) {}
    record Grupo(Long id, UUID coordenador, Integer versao) {}

    @Transactional
    public Resultado criar(CriarGrupoDTO dto) {
        if (dto == null || dto.tema() == null || dto.tema().isBlank() || dto.coordenadorId() == null
                || dto.recorrencia() == null || dto.dataPrimeiraSessao() == null || dto.horario() == null)
            throw new ValidationException("Informe os dados do grupo.");
        var contexto = atores.autorizarOperacional();
        guardas.validarPeriodoRecorrencia(dto);
        var ids = participantes(dto.participantesIds());
        if (dto.dataPrimeiraSessao().isBefore(LocalDate.now(clock)))
            throw new ValidationException("Não é permitido criar grupo com data passada.");
        var datas = guardas.gerarDatasDaSerie(dto.dataPrimeiraSessao(), dto.dataFimRecorrencia(), dto.recorrencia());
        var recursos = coordenacao.bloquear(() -> {
            var locais = locais(ids, contexto.unidade());
            return CoordenacaoEscritaClinicaEnsaio.descoberta(locais, ids, contexto, dto.coordenadorId(),
                    List.of(), List.of(), List.of(), List.of(), locais);
        }, contexto, dto.coordenadorId());
        var responsavel = recursos.atores().referencia();
        var locais = ids.stream().map(recursos.vinculos()::get).toList();
        for (var data : datas) validar(responsavel, locais, data, dto.horario());
        var grupo = new GrupoTerapeutico();
        grupo.setTema(dto.tema());
        grupo.setCoordenador(responsavel);
        grupo.registrarUnidadeDeOrigem(responsavel);
        grupo.setRecorrencia(dto.recorrencia());
        grupo.setHorarioPadrao(dto.horario());
        grupo.setDataFimRecorrencia(dto.dataFimRecorrencia());
        grupo.setAtivo(true);
        em.persist(grupo);
        var sessoes = datas.stream().map(d -> criarSessao(grupo, d, dto.horario(), locais).getId()).toList();
        em.flush();
        return new Resultado(grupo.getId(), grupo.getVersion(), sessoes);
    }

    @Transactional
    public Proxima criarProxima(NovaSessaoDTO dto) {
        if (dto == null || dto.grupoId() == null || dto.dataSessao() == null)
            throw new ValidationException("Informe grupo e data da sessão.");
        var contexto = atores.autorizarOperacional();
        var ids = participantes(dto.participantesIds());
        if (dto.dataSessao().isBefore(LocalDate.now(clock)))
            throw new ValidationException("Não é permitido criar sessão em data passada.");
        var recursos = coordenacao.bloquear(() -> {
            var g = descobrir(dto.grupoId(), contexto.unidade());
            var locais = locais(ids, contexto.unidade());
            return CoordenacaoEscritaClinicaEnsaio.descoberta(locais, ids, contexto, null,
                    List.of(g.coordenador()), List.of(g.id()), List.of(), List.of(), g);
        }, contexto, null);
        var grupo = em.find(GrupoTerapeutico.class, dto.grupoId());
        if (grupo == null || !contexto.unidade().equals(grupo.getUnidadeOrigemChave()))
            throw new ResourceNotFoundException("Grupo não encontrado.");
        if (!grupo.isAtivo()) throw new ValidationException("Grupo inativo.");
        if (!AtoresEstadoClinicoLocalEnsaio.elegivel(grupo.getCoordenador(), contexto.unidade(), false))
            throw new ConflictException("Coordenação incompatível com a unidade.");
        // O lock do grupo serializa criação e alteração da série. Não ignorar
        // sessões legadas ou externas com origem divergente.
        var sessoes = em.createQuery("select s from SessaoGrupo s where s.grupo.id = :id", SessaoGrupo.class)
                .setParameter("id", grupo.getId()).getResultList();
        if (sessoes.stream().anyMatch(s -> !contexto.unidade().equals(s.getUnidadeOrigemChave())
                || s.getDataSessao() == null || s.getStatus() == null))
            throw new ConflictException("Série com origem ou estado pendente de conferência.");
        if (sessoes.stream().anyMatch(s -> dto.dataSessao().equals(s.getDataSessao())
                && s.getStatus() != StatusSessaoGrupo.CANCELADA))
            throw new ConflictException("Já existe sessão nessa data.");
        var horario = dto.horario() == null ? grupo.getHorarioPadrao() : dto.horario();
        if (horario == null) throw new ValidationException("Informe o horário da sessão.");
        var locais = ids.stream().map(recursos.vinculos()::get).toList();
        validar(grupo.getCoordenador(), locais, dto.dataSessao(), horario);
        var s = criarSessao(grupo, dto.dataSessao(), horario, locais);
        em.flush();
        return new Proxima(s.getId(), s.getVersion());
    }

    record Sessao(Long id, LocalDate data, LocalTime hora, StatusSessaoGrupo status, UUID responsavel,
            Integer versao, String origem) {}
    record Inscrito(Long id, Long sessao, UUID paciente, StatusPresencaGrupo frequencia) {}
    record Serie(Grupo grupo, List<Sessao> sessoes, List<Inscrito> inscritos) {}

    private Descoberta<Serie> descobrirSerie(Long id, AtoresEstadoClinicoLocalEnsaio.Contexto contexto,
            UUID candidato, UUID novoCoordenador) {
        var g = descobrir(id, contexto.unidade());
        var sessoes = em.createQuery("""
                select new com.pet.buscaativa.services.impl.SerieGruposLocaisEnsaioService$Sessao(
                    s.id, s.dataSessao, s.horario, s.status, r.idPublico, s.version, s.unidadeOrigemChave)
                from SessaoGrupo s left join s.responsavelAtendimento r where s.grupo.id=:id order by s.id
                """, Sessao.class).setParameter("id", id).getResultList();
        if (sessoes.stream().anyMatch(s -> !contexto.unidade().equals(s.origem()) || s.data()==null
                || s.hora()==null || s.status()==null))
            throw new ConflictException("Série com origem ou estado pendente de conferência.");
        var inscritos = em.createQuery("""
                select new com.pet.buscaativa.services.impl.SerieGruposLocaisEnsaioService$Inscrito(
                    p.id, p.sessaoGrupo.id, p.paciente.idPublico, p.statusPresenca)
                from SessaoGrupoParticipante p where p.sessaoGrupo.grupo.id=:id order by p.id
                """, Inscrito.class).setParameter("id", id).getResultList();
        var pacientes = new TreeSet<UUID>();
        inscritos.forEach(i -> pacientes.add(i.paciente()));
        if (candidato != null) pacientes.add(candidato);
        var ids = List.copyOf(pacientes);
        var responsaveis = new ArrayList<UUID>();
        responsaveis.add(g.coordenador());
        sessoes.stream().map(Sessao::responsavel).filter(Objects::nonNull).forEach(responsaveis::add);
        return CoordenacaoEscritaClinicaEnsaio.descoberta(locais(ids, contexto.unidade()), ids, contexto,
                novoCoordenador, responsaveis, List.of(id), sessoes.stream().map(Sessao::id).toList(), List.of(),
                new Serie(g, List.copyOf(sessoes), List.copyOf(inscritos)));
    }

    private GrupoTerapeutico grupoBloqueado(Long id, String unidade) {
        var g = em.find(GrupoTerapeutico.class, id);
        if (g == null || !unidade.equals(g.getUnidadeOrigemChave()))
            throw new ResourceNotFoundException("Grupo não encontrado.");
        if (!AtoresEstadoClinicoLocalEnsaio.elegivel(g.getCoordenador(), unidade, false))
            throw new ConflictException("Coordenação incompatível com a unidade.");
        return g;
    }

    private List<SessaoGrupo> sessoes(CoordenacaoEscritaClinicaEnsaio.Bloqueados<Serie> recursos) {
        var ordenadas = recursos.sessoes().values().stream().sorted(Comparator.comparing(SessaoGrupo::getDataSessao)
                .thenComparing(SessaoGrupo::getHorario).thenComparing(SessaoGrupo::getId)).toList();
        if (ordenadas.isEmpty()) throw new ValidationException("Grupo sem sessões.");
        return ordenadas;
    }

    private static void exigirResponsavel(Usuario ator, Usuario responsavel, boolean recepcao) {
        if (ator.getTipoUsuario() == TipoUsuario.ADMINISTRADOR
                || recepcao && ator.getTipoUsuario() == TipoUsuario.RECEPCAO) return;
        if (ator.getTipoUsuario() != TipoUsuario.PROFISSIONAL || responsavel == null
                || !Objects.equals(ator.getId(), responsavel.getId()))
            throw new AccessDeniedException("Usuário sem autorização para esta operação.");
    }

    /** Mantém responsabilidade histórica; regeneração somente antes do início. */
    @Transactional
    public Resultado atualizar(Long id, AtualizarGrupoTerapeuticoDTO dto) {
        if (dto == null || dto.tema()==null || dto.tema().isBlank() || dto.coordenadorId()==null
                || dto.dataPrimeiraSessao()==null || dto.horario()==null || dto.recorrencia()==null)
            throw new ValidationException("Informe os dados do grupo.");
        var contexto = atores.autorizarOperacional();
        var recursos = coordenacao.bloquear(() -> descobrirSerie(id, contexto, null, dto.coordenadorId()),
                contexto, dto.coordenadorId());
        var g = grupoBloqueado(id, contexto.unidade());
        exigirResponsavel(recursos.atores().ator(), g.getCoordenador(), true);
        CoordenacaoEscritaClinicaEnsaio.validarVersao(dto.version(), g.getVersion());
        var sessoes = sessoes(recursos);
        var novo = recursos.atores().referencia();
        boolean estrutural = !sessoes.getFirst().getDataSessao().equals(dto.dataPrimeiraSessao())
                || !Objects.equals(g.getHorarioPadrao(), dto.horario()) || g.getRecorrencia()!=dto.recorrencia()
                || !Objects.equals(g.getDataFimRecorrencia(), dto.dataFimRecorrencia());
        boolean troca = !Objects.equals(g.getCoordenador().getId(), novo.getId());
        var agora = LocalDateTime.now(clock);
        List<Long> ids;
        if (estrutural) {
            if (sessoes.stream().anyMatch(s -> s.getStatus()!=StatusSessaoGrupo.AGENDADA
                    || !designacoes.aindaNaoIniciou(s, agora)))
                throw new ConflictException("Após o início do grupo não é permitido alterar a estrutura.");
            var participantes = sessoes.getFirst().getParticipantes().stream().map(x -> x.getPaciente().getIdPublico())
                    .collect(Collectors.toSet());
            if (sessoes.stream().anyMatch(s -> !s.getParticipantes().stream().map(x -> x.getPaciente().getIdPublico())
                    .collect(Collectors.toSet()).equals(participantes)
                    || s.getParticipantes().stream().anyMatch(x -> x.getStatusPresenca()!=StatusPresencaGrupo.NAO_REGISTRADA)))
                throw new ConflictException("Série com composição ou frequência incompatível com regeneração.");
            guardas.validarPeriodoRecorrencia(new CriarGrupoDTO(dto.tema(), dto.coordenadorId(), dto.recorrencia(),
                    dto.dataPrimeiraSessao(), dto.dataFimRecorrencia(), dto.horario(), List.copyOf(participantes)));
            if (dto.dataPrimeiraSessao().isBefore(LocalDate.now(clock)))
                throw new ValidationException("Primeira sessão no passado.");
            var datas = guardas.gerarDatasDaSerie(dto.dataPrimeiraSessao(), dto.dataFimRecorrencia(), dto.recorrencia());
            var locais = participantes.stream().sorted().map(recursos.vinculos()::get).toList();
            var origens = new HashMap<LocalDateTime,Long>();
            for (var s : sessoes) if (origens.putIfAbsent(LocalDateTime.of(s.getDataSessao(), s.getHorario()), s.getId())!=null)
                throw new ConflictException("Série com sessões simultâneas.");
            for (var data : datas) {
                Long excluir = origens.get(LocalDateTime.of(data,dto.horario()));
                guardas.validarCompromissoCoordenador(novo,data,dto.horario(),excluir);
                for (var local : locais) {
                    exigirAtivo(local);
                    guardas.validarNovaParticipacao(local.getPaciente(),data,dto.horario(),excluir,local);
                }
            }
            sessoes.forEach(em::remove);
            em.flush();
            g.setCoordenador(novo);
            ids = datas.stream().map(d -> criarSessao(g,d,dto.horario(),locais).getId()).toList();
        } else {
            if (troca) {
                for (var s : sessoes) {
                    if (s.getStatus() == StatusSessaoGrupo.AGENDADA && !s.isResponsabilidadePendente()
                            && designacoes.aindaNaoIniciou(s, agora))
                        guardas.validarCompromissoCoordenador(novo, s.getDataSessao(), s.getHorario(), s.getId());
                }
                sessoes.forEach(s -> designacoes.transferir(s, novo, agora));
            }
            ids = sessoes.stream().map(SessaoGrupo::getId).toList();
        }
        g.setTema(dto.tema().trim());
        g.setCoordenador(novo);
        g.setHorarioPadrao(dto.horario());
        g.setRecorrencia(dto.recorrencia());
        g.setDataFimRecorrencia(dto.dataFimRecorrencia());
        em.flush();
        return new Resultado(id,g.getVersion(),ids);
    }

    @Transactional
    public List<Long> inscreverFuturas(Long id, InscricaoFuturaGrupoDTO dto) {
        if (dto==null || dto.pacienteId()==null) throw new ValidationException("Informe o paciente.");
        return editarFuturas(id,dto.pacienteId(),true);
    }

    @Transactional
    public List<Long> removerFuturas(Long id, UUID paciente) {
        if (paciente==null) throw new ValidationException("Informe o paciente.");
        return editarFuturas(id,paciente,false);
    }

    private List<Long> editarFuturas(Long id, UUID paciente, boolean incluir) {
        var contexto = atores.autorizar(false);
        var recursos = coordenacao.bloquear(() -> descobrirSerie(id,contexto,paciente,null),contexto,null);
        grupoBloqueado(id,contexto.unidade());
        var local = recursos.vinculos().get(paciente);
        if (incluir) exigirAtivo(local);
        var futuras = sessoes(recursos).stream().filter(s -> s.getStatus()==StatusSessaoGrupo.AGENDADA
                && guardas.sessaoAindaNaoIniciou(s)).toList();
        if (futuras.isEmpty()) throw new ValidationException("Não existem sessões futuras.");
        var alterar = new ArrayList<SessaoGrupo>();
        for (var s : futuras) {
            var p = participante(s,paciente);
            if (incluir) {
                // Inclusive inscrições existentes: não ocultar reserva individual ativa.
                if (p.isEmpty()) guardas.validarNovaParticipacao(local.getPaciente(),s.getDataSessao(),s.getHorario(),s.getId(),local);
                if (em.createQuery("""
                        select count(a) from Agendamento a where a.paciente.id=:paciente and a.dataAgendamento=:data
                        and a.horaAtendimento=:hora and a.ocupaVaga=true
                        """,Long.class).setParameter("paciente",local.getPaciente().getId())
                        .setParameter("data",s.getDataSessao()).setParameter("hora",s.getHorario()).getSingleResult()>0)
                    throw new ConflictException("Compromisso assistencial incompatível.");
                if (p.isEmpty()) alterar.add(s);
            } else if (p.isPresent()) alterar.add(s);
        }
        if (!incluir && alterar.isEmpty()) throw new ValidationException("Não existem inscrições futuras para remover.");
        for (var s : alterar) {
            if (incluir) incluir(s,local);
            else {
                var p = participante(s,paciente).orElseThrow();
                s.getParticipantes().remove(p);
                em.remove(p);
            }
            em.lock(s,LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        }
        em.flush();
        return alterar.stream().map(SessaoGrupo::getId).toList();
    }

    /** Fatos de todas as sessões realizadas e inscrições futuras em uma única transação. */
    @Transactional
    public List<Long> inscreverRetroativamente(Long id, InscricaoRetroativaGrupoDTO dto) {
        if (dto==null || dto.pacienteId()==null || dto.frequenciasPassadas()==null)
            throw new ValidationException("Informe paciente e frequências.");
        var contexto = atores.autorizar(false);
        var recursos = coordenacao.bloquear(() -> descobrirSerie(id,contexto,dto.pacienteId(),null),contexto,null);
        var g = grupoBloqueado(id,contexto.unidade());
        var ator = recursos.atores().ator();
        exigirResponsavel(ator,g.getCoordenador(),false);
        var local = recursos.vinculos().get(dto.pacienteId());
        exigirAtivo(local);
        var sessoes = sessoes(recursos);
        var informadas = new HashMap<Long,StatusPresencaGrupo>();
        for (var f : dto.frequenciasPassadas()) {
            if (f==null || f.sessaoId()==null || !AvaliacaoProvenienciaClinica.frequenciaRegistrada(f.statusPresenca()))
                throw new ValidationException("Informe presença ou falta.");
            if (informadas.putIfAbsent(f.sessaoId(),f.statusPresenca())!=null)
                throw new ConflictException("Sessão repetida.");
        }
        var realizadas = sessoes.stream().filter(s -> s.getStatus()==StatusSessaoGrupo.REALIZADA).toList();
        if (!informadas.keySet().equals(realizadas.stream().map(SessaoGrupo::getId).collect(Collectors.toSet())))
            throw new ValidationException("Informe todas e somente as sessões realizadas.");
        for (var s : sessoes) {
            var p = participante(s,dto.pacienteId());
            if (s.getStatus()==StatusSessaoGrupo.REALIZADA) {
                if (!AvaliacaoProvenienciaClinica.responsabilidadeRegistrada(s))
                    throw new ConflictException("Responsabilidade histórica pendente.");
                exigirResponsavel(ator,s.getResponsavelAtendimento(),false);
                temporal.validarSessao(s,false);
                if (p.isPresent() || !historicos.findAllByPacienteAndSessaoGrupo(local.getPaciente(),s).isEmpty())
                    throw new ConflictException("Paciente já possui participação ou histórico nesta sessão.");
                if (informadas.get(s.getId())==StatusPresencaGrupo.PRESENTE)
                    guardas.validarPresencaColetiva(local.getPaciente(),s,local);
            } else {
                if (p.isPresent() && p.get().getStatusPresenca()!=StatusPresencaGrupo.NAO_REGISTRADA)
                    throw new ConflictException("Frequência anterior não pode ser sobrescrita.");
                if (s.getStatus()==StatusSessaoGrupo.AGENDADA) {
                    if (LocalDateTime.of(s.getDataSessao(),s.getHorario()).isBefore(LocalDateTime.now(clock)))
                        throw new ConflictException("Confirme a sessão passada antes da inscrição.");
                    if (p.isEmpty()) guardas.validarNovaParticipacao(local.getPaciente(),s.getDataSessao(),s.getHorario(),s.getId(),local);
                }
            }
        }
        // A pré-validação externa não vê ainda fatos do próprio lote.
        var presentes = new HashSet<LocalDateTime>();
        for (var s : realizadas) if (informadas.get(s.getId())==StatusPresencaGrupo.PRESENTE
                && !presentes.add(LocalDateTime.of(s.getDataSessao(),s.getHorario())))
            throw new ConflictException("Presenças simultâneas no lote.");
        var audit = new InscricaoRetroativaGrupoAuditoria();
        audit.setGrupo(g);
        audit.setPaciente(local.getPaciente());
        var fatos = new ArrayList<SessaoGrupoParticipante>();
        var alteradas = new ArrayList<Long>();
        for (var s : sessoes) {
            if (s.getStatus()==StatusSessaoGrupo.CANCELADA || participante(s,dto.pacienteId()).isPresent()) continue;
            var p = incluir(s,local);
            if (s.getStatus()==StatusSessaoGrupo.REALIZADA) {
                p.setStatusPresenca(informadas.get(s.getId()));
                fatos.add(p);
                var item = new InscricaoRetroativaGrupoFrequenciaAuditoria();
                item.setAuditoria(audit);
                item.setSessao(s);
                item.setDataSessao(s.getDataSessao());
                item.setStatusPresenca(p.getStatusPresenca());
                audit.getFrequencias().add(item);
            }
            em.lock(s,LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            alteradas.add(s.getId());
        }
        em.flush();
        indicadores.aplicar(indicadores.avaliar(local),ator);
        fatos.forEach(p -> historico.registrarGrupo(p,null,"Frequência de inscrição retroativa local."));
        em.persist(audit);
        em.flush();
        historico.renumerar(local);
        return List.copyOf(alteradas);
    }

    private void exigirAtivo(VinculoAssistencial local) {
        if (!evidencia.analisar(local).comprovada() || local.getStatusPaciente()!=StatusPaciente.ATIVO)
            throw new ConflictException("Acompanhamento local ativo não comprovado.");
    }

    private static Optional<SessaoGrupoParticipante> participante(SessaoGrupo s, UUID paciente) {
        return s.getParticipantes().stream().filter(p -> paciente.equals(p.getPaciente().getIdPublico())).findFirst();
    }

    private SessaoGrupoParticipante incluir(SessaoGrupo s, VinculoAssistencial local) {
        var p = new SessaoGrupoParticipante();
        p.setPaciente(local.getPaciente());
        p.setSessaoGrupo(s);
        em.persist(p);
        s.getParticipantes().add(p);
        return p;
    }

    private Grupo descobrir(Long id, String unidade) {
        return em.createQuery("""
                select new com.pet.buscaativa.services.impl.SerieGruposLocaisEnsaioService$Grupo(
                    g.id, g.coordenador.idPublico, g.version)
                from GrupoTerapeutico g where g.id = :id and g.unidadeOrigemChave = :unidade
                """, Grupo.class).setParameter("id", id).setParameter("unidade", unidade)
                .getResultList().stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado."));
    }

    private List<CoordenacaoEscritaClinicaEnsaio.IdentidadeVinculo> locais(List<UUID> ids, String unidade) {
        return ids.isEmpty() ? List.of() : coordenacao.identificar(ids, unidade, true);
    }

    private List<UUID> participantes(List<UUID> ids) {
        guardas.validarIdsParticipantes(ids);
        if (ids == null) return List.of();
        if (ids.stream().anyMatch(java.util.Objects::isNull)) throw new ValidationException("Participante inválido.");
        return List.copyOf(ids);
    }

    private void validar(Usuario responsavel, List<VinculoAssistencial> locais, LocalDate data, LocalTime hora) {
        guardas.validarCompromissoCoordenador(responsavel, data, hora, null);
        for (var local : locais) {
            if (!evidencia.analisar(local).comprovada())
                throw new ConflictException("Estado local do acompanhamento pendente de comprovação.");
            guardas.validarNovaParticipacao(local.getPaciente(), data, hora, null, local);
        }
    }

    private SessaoGrupo criarSessao(GrupoTerapeutico grupo, LocalDate data, LocalTime hora, List<VinculoAssistencial> locais) {
        var sessao = guardas.novaSessao(grupo, data, hora);
        em.persist(sessao);
        for (var local : locais) {
            var participante = new SessaoGrupoParticipante();
            participante.setPaciente(local.getPaciente());
            participante.setSessaoGrupo(sessao);
            em.persist(participante);
            sessao.getParticipantes().add(participante);
        }
        return sessao;
    }
}
