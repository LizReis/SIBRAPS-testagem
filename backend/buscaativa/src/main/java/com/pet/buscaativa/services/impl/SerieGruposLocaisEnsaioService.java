package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
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
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class SerieGruposLocaisEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final GrupoTerapeuticoServiceImpl guardas;
    private final EvidenciaStatusVinculo evidencia;
    private final EntityManager em;
    private final Clock clock;

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
