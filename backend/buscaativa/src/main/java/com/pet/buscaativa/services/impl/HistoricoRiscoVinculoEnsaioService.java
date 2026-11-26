package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.exception.RevisionDoesNotExistException;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.impl.BuscaAtivaPainelLocalEnsaioService.Evolucao;
import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService.Evento;
import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService.Fonte;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
class HistoricoRiscoVinculoEnsaioService {
    private static final DateTimeFormatter ROTULO = DateTimeFormatter.ofPattern("MMM/yy", Locale.forLanguageTag("pt-BR"));
    private final EntityManager em;
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final EvidenciaStatusVinculo evidencia;
    private final ObjectProvider<CoberturaHistoricaPorUnidade> cobertura;
    private final Clock clock;
    private final ObjectProvider<FechamentoHistoricoLocalService> fechamentos;

    List<Evolucao> reconstruir(List<YearMonth> meses, TipoAcompanhamento tipo, Boolean rua) {
        String unidade = atores.autorizarOperacional().unidade();
        var persistidos = fechamentos.getIfAvailable();
        if (persistidos != null && persistidos.habilitado())
            return persistidos.ler(unidade, meses, tipo, rua);
        var fonte = cobertura.getIfUnique();
        if (fonte == null) return meses.stream().map(HistoricoRiscoVinculoEnsaioService::semCobertura).toList();
        var leitor = AuditReaderFactory.get(em);
        return meses.stream().map(mes -> {
            try {
                return fechamento(leitor, fonte, unidade, mes, tipo, rua);
            } catch (jakarta.persistence.EntityNotFoundException | org.hibernate.ObjectNotFoundException referenciaSemRevisao) {
                return indisponivel(mes);
            }
        }).toList();
    }

    private Evolucao fechamento(AuditReader leitor, CoberturaHistoricaPorUnidade fonte,
            String unidade, YearMonth mes, TipoAcompanhamento tipo, Boolean rua) {
        if (fonte == null || !mes.isBefore(YearMonth.now(clock))) return indisponivel(mes);
        // Mesmo corte do painel legado. A revisão posterior não reescreve o fechamento.
        var corte = corte(mes, clock.getZone());
        var fim = LocalDateTime.ofInstant(corte, clock.getZone());
        var inventario = fonte.vinculosCertificados(unidade, corte);
        if (inventario.isEmpty() || !vinculosAtuaisAuditados(leitor, unidade)) return indisponivel(mes);

        Number revisao;
        try {
            revisao = leitor.getRevisionNumberForDate(Date.from(corte));
        } catch (RevisionDoesNotExistException semRevisao) {
            return inventario.orElseThrow().isEmpty() ? distribuir(mes, new EnumMap<>(ClassificacaoRisco.class))
                    : indisponivel(mes);
        }
        // Um prefixo de IDs com revisão posterior ao corte faria o Envers incluir dados futuros.
        Number foraDoCorte = (Number) em.createNativeQuery("""
                select count(*) from revinfo where rev <= :rev and revtstmp > :corte
                """).setParameter("rev", revisao).setParameter("corte", corte.toEpochMilli()).getSingleResult();
        if (foraDoCorte.longValue() != 0) return indisponivel(mes);
        List<?> locais = leitor.createQuery().forEntitiesAtRevision(VinculoAssistencial.class, revisao)
                .add(AuditEntity.property("unidadeChave").eq(unidade)).getResultList();
        var ids = locais.stream().map(VinculoAssistencial.class::cast).map(VinculoAssistencial::getId)
                .collect(Collectors.toSet());
        if (!ids.equals(inventario.orElseThrow())) return indisponivel(mes);
        var contagem = new EnumMap<ClassificacaoRisco, Long>(ClassificacaoRisco.class);
        var pacientes = new java.util.HashSet<Long>();
        for (Object item : locais) {
            var v = (VinculoAssistencial) item;
            if (!ProjecaoClinicaVinculoService.chaveCanonica(v) || v.getPaciente() == null
                    || !pacientes.add(v.getPaciente().getId())
                    || !evidencia.comprovadaAte(leitor, v, revisao, fim)) return indisponivel(mes);
            if (v.getStatusPaciente() != StatusPaciente.ATIVO) continue;
            var modalidade = v.getTipoAcompanhamento();
            if (tipo != null && modalidade == null) return indisponivel(mes);
            if (tipo != null && tipo != modalidade) continue;
            if (rua != null) {
                List<?> ruaAuditada = leitor.createQuery().forEntitiesAtRevision(Paciente.class, revisao)
                        .add(AuditEntity.id().eq(v.getPaciente().getId()))
                        .addProjection(AuditEntity.property("situacaoRua")).getResultList();
                if (ruaAuditada.size() != 1 || !(ruaAuditada.getFirst() instanceof Boolean valor))
                    return indisponivel(mes);
                if (!valor.equals(rua)) continue;
            }
            var risco = fatos(leitor, revisao, v, fim.toLocalDate(), corte)
                    .flatMap(f -> ComparadorPoliticaRiscoVinculo.comparar(f, StatusPaciente.ATIVO,
                            modalidade, v.getIndicadoresPendentes(), fim.toLocalDate()));
            if (risco.isEmpty()) return indisponivel(mes);
            contagem.merge(risco.orElseThrow(), 1L, Long::sum);
        }
        return distribuir(mes, contagem);
    }

    static Instant corte(YearMonth mes, ZoneId zona) {
        return mes.plusMonths(1).atDay(1).atStartOfDay(zona).toInstant().minusMillis(1);
    }

    boolean inventarioConfere(String unidade, YearMonth mes, Set<Long> manifesto) {
        var leitor = AuditReaderFactory.get(em);
        try {
            var revisao = leitor.getRevisionNumberForDate(Date.from(corte(mes, clock.getZone())));
            List<?> ids = leitor.createQuery().forEntitiesAtRevision(VinculoAssistencial.class, revisao)
                    .add(AuditEntity.property("unidadeChave").eq(unidade)).addProjection(AuditEntity.id()).getResultList();
            return manifesto.equals(new java.util.HashSet<>(ids)) && vinculosAtuaisAuditados(leitor, unidade);
        } catch (RevisionDoesNotExistException semRevisao) {
            return manifesto.isEmpty() && vinculosAtuaisAuditados(leitor, unidade);
        }
    }

    Evolucao conferirFechamento(String unidade, YearMonth mes, Set<Long> manifesto, TipoAcompanhamento tipo, Boolean rua) {
        try {
            return fechamento(AuditReaderFactory.get(em), (u, c) -> Optional.of(manifesto), unidade, mes, tipo, rua);
        } catch (jakarta.persistence.EntityNotFoundException | org.hibernate.ObjectNotFoundException referenciaSemRevisao) {
            return indisponivel(mes);
        }
    }

    private boolean vinculosAtuaisAuditados(AuditReader leitor, String unidade) {
        var atuais = em.createQuery("select v.id from VinculoAssistencial v where v.unidadeChave=:unidade", Long.class)
                .setParameter("unidade", unidade).getResultList();
        for (Long id : atuais) {
            if (leitor.createQuery().forRevisionsOfEntity(VinculoAssistencial.class, false, true)
                    .add(AuditEntity.id().eq(id)).add(AuditEntity.revisionType().eq(RevisionType.ADD))
                    .setMaxResults(1).getResultList().isEmpty()) return false;
        }
        return true;
    }

    private Optional<DiagnosticoFaltasConsecutivasVinculoService.Resultado> fatos(AuditReader leitor, Number revisao,
            VinculoAssistencial vinculo, LocalDate data, Instant corte) {
        Long paciente = vinculo.getPaciente().getId();
        String unidade = vinculo.getUnidadeChave();
        List<?> agenda = leitor.createQuery().forEntitiesAtRevision(Agendamento.class, revisao)
                .add(AuditEntity.relatedId("paciente").eq(paciente)).getResultList();
        var sucessores = new HashMap<Long, Long>();
        for (Object item : agenda) {
            var a = (Agendamento) item;
            if (a.getAgendamentoOriginal() != null) sucessores.merge(a.getAgendamentoOriginal().getId(), 1L, Long::sum);
        }
        var eventos = new ArrayList<Evento>();
        var responsaveis = new HashMap<ProjecaoResolucaoFrequencia.Chave,Long>();
        for (Object item : agenda) {
            var a = (Agendamento) item;
            if (a.getDataAgendamento() != null && a.getDataAgendamento().isAfter(data)) continue;
            String origem = a.getUnidadeOrigemChave();
            String cadeia = origem == null ? unidade : origem;
            var prova = AvaliacaoProvenienciaClinica.cadeiaAgendamento(a, paciente, cadeia,
                    (original, sucessor) -> sucessores.getOrDefault(original.getId(), 0L) != 1L);
            if (prova != AvaliacaoProvenienciaClinica.Situacao.COMPROVADA
                    || !origemAuditada(leitor, Agendamento.class, a.getId(), revisao, origem)) return Optional.empty();
            if (!unidade.equals(origem) || a.getFrequenciaAssistencial() == FrequenciaAssistencial.NAO_REGISTRADA) continue;
            if (!AvaliacaoProvenienciaClinica.frequenciaIndividualRegistrada(a)) return Optional.empty();
            eventos.add(new Evento(LocalDateTime.of(a.getDataAgendamento(), a.getHoraAtendimento()),
                    a.getFrequenciaAssistencial() == FrequenciaAssistencial.PRESENTE, Fonte.ATENDIMENTO_INDIVIDUAL, a.getId()));
            if(a.getUsuario()!=null) responsaveis.put(new ProjecaoResolucaoFrequencia.Chave(Fonte.ATENDIMENTO_INDIVIDUAL,a.getId()),a.getUsuario().getId());
        }

        List<?> participacoes = leitor.createQuery().forEntitiesAtRevision(SessaoGrupoParticipante.class, revisao)
                .add(AuditEntity.relatedId("paciente").eq(paciente)).getResultList();
        for (Object item : participacoes) {
            var p = (SessaoGrupoParticipante) item;
            var s = p.getSessaoGrupo();
            if (s == null) return Optional.empty();
            if (s.getDataSessao() != null && s.getDataSessao().isAfter(data)) continue;
            if (AvaliacaoProvenienciaClinica.origemSessao(s) != AvaliacaoProvenienciaClinica.Situacao.COMPROVADA
                    || !origemAuditada(leitor, SessaoGrupo.class, s.getId(), revisao, s.getUnidadeOrigemChave())
                    || !origemAuditada(leitor, GrupoTerapeutico.class, s.getGrupo().getId(), revisao, s.getUnidadeOrigemChave()))
                return Optional.empty();
            if (!unidade.equals(s.getUnidadeOrigemChave())) continue;
            if (s.getStatus() != StatusSessaoGrupo.REALIZADA && p.getStatusPresenca() == StatusPresencaGrupo.NAO_REGISTRADA) continue;
            if (s.getStatus() != StatusSessaoGrupo.REALIZADA
                    || !AvaliacaoProvenienciaClinica.frequenciaRegistrada(p.getStatusPresenca())) return Optional.empty();
            eventos.add(new Evento(LocalDateTime.of(s.getDataSessao(), s.getHorario()),
                    p.getStatusPresenca() == StatusPresencaGrupo.PRESENTE, Fonte.SESSAO_GRUPO, p.getId()));
            if(s.getResponsavelAtendimento()!=null) responsaveis.put(new ProjecaoResolucaoFrequencia.Chave(Fonte.SESSAO_GRUPO,p.getId()),s.getResponsavelAtendimento().getId());
        }
        var resolucoes=resolucoesAte(vinculo,corte);
        var fatos=eventos.stream().map(e->new ProjecaoResolucaoFrequencia.Fato(e,
                responsaveis.get(new ProjecaoResolucaoFrequencia.Chave(e.fonte(),e.registroId())))).toList();
        var projecao=ProjecaoResolucaoFrequencia.aplicar(fatos,resolucoes);
        if(projecao.inconclusivo()) return Optional.empty();
        eventos.removeIf(e->projecao.desconsiderados().contains(new ProjecaoResolucaoFrequencia.Chave(e.fonte(),e.registroId())));
        eventos.sort(Comparator.comparing(Evento::ocorridoEm).thenComparing(Evento::fonte).thenComparing(Evento::registroId));
        return Optional.of(DiagnosticoFaltasConsecutivasVinculoService.calcular(
                new LinhaDoTempoFrequenciasVinculoService.Diagnostico(List.copyOf(eventos), 0, 0, 0, 0)));
    }

    private List<ProjecaoResolucaoFrequencia.Resolucao> resolucoesAte(VinculoAssistencial v,Instant corte) {
        boolean instalada=((Number)em.createNativeQuery("""
                select count(*) from information_schema.tables
                where table_schema=current_schema() and table_name='VW_RESOLUCAO_FREQUENCIA_ATIVA'
                """).getSingleResult()).longValue()==1;
        if(!instalada) return List.of();
        // Não usar a visão ativa ATUAL: reversões posteriores ao corte
        // não modificam a validade do fato no mês reconstruído.
        @SuppressWarnings("unchecked")
        List<Object[]> linhas=em.createNativeQuery("""
                select r.fonte_valida,r.fato_valido,r.fonte_desconsiderada,r.fato_desconsiderado,
                       r.horario,r.frequencia_valida,r.frequencia_desconsiderada,r.responsavel_valido,r.responsavel_desconsiderado
                from tb_resolucao_frequencia_local r
                where r.vinculo_id=:vinculo and r.paciente_id=:paciente and r.unidade_chave=:unidade
                  and r.registrado_em<=:corte
                  and not exists(select 1 from tb_reversao_resolucao_frequencia d
                       where d.resolucao_id=r.id and d.registrado_em<=:corte)
                """).setParameter("vinculo",v.getId()).setParameter("paciente",v.getPaciente().getId())
                .setParameter("unidade",v.getUnidadeChave()).setParameter("corte",java.time.OffsetDateTime.ofInstant(corte,java.time.ZoneOffset.UTC))
                .getResultList();
        return linhas.stream().map(r->new ProjecaoResolucaoFrequencia.Resolucao(
                new ProjecaoResolucaoFrequencia.Chave(Fonte.valueOf(r[0].toString()),((Number)r[1]).longValue()),
                new ProjecaoResolucaoFrequencia.Chave(Fonte.valueOf(r[2].toString()),((Number)r[3]).longValue()),
                r[4] instanceof LocalDateTime t?t:((java.sql.Timestamp)r[4]).toLocalDateTime(),
                "PRESENTE".equals(r[5]),"PRESENTE".equals(r[6]),
                ((Number)r[7]).longValue(),((Number)r[8]).longValue())).toList();
    }

    private boolean origemAuditada(AuditReader leitor, Class<?> tipo, Long id, Number corte, String origem) {
        if (origem == null || !(origem.matches("USF:[1-9][0-9]*")
                || List.of("CAPS_I", "CAPS_II", "CAPS_AD").contains(origem))) return false;
        List<?> trilha = leitor.createQuery().forRevisionsOfEntity(tipo, false, true)
                .add(AuditEntity.id().eq(id)).add(AuditEntity.revisionNumber().le(corte))
                .addProjection(AuditEntity.revisionType()).addProjection(AuditEntity.property("unidadeOrigemChave"))
                .addOrder(AuditEntity.revisionNumber().asc()).getResultList();
        return !trilha.isEmpty() && ((Object[]) trilha.getFirst())[0] == RevisionType.ADD
                && trilha.stream().map(Object[].class::cast).allMatch(r -> r[0] != RevisionType.DEL && origem.equals(r[1]));
    }

    static Evolucao semCobertura(YearMonth mes) {
        return new Evolucao(mes.toString(), mes.format(ROTULO), null, null, null, false,
                BuscaAtivaPainelLocalEnsaioService.SituacaoHistorica.SEM_COBERTURA);
    }

    private static Evolucao indisponivel(YearMonth mes) {
        return new Evolucao(mes.toString(), mes.format(ROTULO), null, null, null, false);
    }

    private static Evolucao distribuir(YearMonth mes, EnumMap<ClassificacaoRisco, Long> contagem) {
        return new Evolucao(mes.toString(), mes.format(ROTULO), contagem.getOrDefault(ClassificacaoRisco.VERDE, 0L),
                contagem.getOrDefault(ClassificacaoRisco.AMARELO, 0L), contagem.getOrDefault(ClassificacaoRisco.VERMELHO, 0L), true);
    }
}
