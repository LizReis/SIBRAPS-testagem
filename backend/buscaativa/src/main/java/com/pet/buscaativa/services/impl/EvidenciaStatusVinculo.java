package com.pet.buscaativa.services.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.DefaultRevisionEntity;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Component;

import com.pet.buscaativa.entities.EventoEstadoClinicoVinculo;
import com.pet.buscaativa.entities.EventoEstadoClinicoVinculo.Natureza;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.StatusPaciente;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Lê a cadeia de estado na mesma transação do diagnóstico. */
@Component
@RequiredArgsConstructor
public class EvidenciaStatusVinculo {
    private final EntityManager em;

    public enum Situacao {
        INICIALIZACAO_AUDITADA, TRANSICAO_AUDITADA, SEM_COMPROVACAO, AUSENTE, CONFLITANTE;

        public boolean comprovada() {
            return this == INICIALIZACAO_AUDITADA || this == TRANSICAO_AUDITADA;
        }
    }

    private record Revisao<T>(T estado, int numero, RevisionType tipo) {}

    public Situacao analisar(VinculoAssistencial vinculo) {
        var leitor = AuditReaderFactory.get(em);
        var revisoes = revisoesVinculo(leitor, vinculo.getId(), null);
        var eventos = revisoesEventos(leitor, vinculo.getId(), null);
        var atuais = em.createQuery("""
                select e from EventoEstadoClinicoVinculo e where e.vinculo.id = :id order by e.id
                """, EventoEstadoClinicoVinculo.class).setParameter("id", vinculo.getId()).getResultList();

        // Detecta eventos sem auditoria e discrepâncias entre a linha atual e sua criação.
        if (eventos.size() != atuais.size()) return Situacao.CONFLITANTE;
        for (int i = 0; i < atuais.size(); i++) {
            var auditado = eventos.get(i);
            if (auditado.tipo() != RevisionType.ADD || !mesmoEvento(atuais.get(i), auditado.estado(), vinculo))
                return Situacao.CONFLITANTE;
        }
        return conferir(vinculo, revisoes, eventos);
    }

    /** A série histórica exige criação inicial comprovada, inclusive quando houve transições. */
    boolean comprovadaAte(AuditReader leitor, VinculoAssistencial historico, Number corte, LocalDateTime fim) {
        var revisoes = revisoesVinculo(leitor, historico.getId(), corte);
        var eventos = revisoesEventos(leitor, historico.getId(), corte);
        return !revisoes.isEmpty() && !eventos.isEmpty()
                && revisoes.getFirst().tipo() == RevisionType.ADD
                && eventos.getFirst().numero() == revisoes.getFirst().numero()
                && eventos.getFirst().estado().getNatureza() == Natureza.INICIALIZACAO
                && eventos.stream().allMatch(e -> e.tipo() == RevisionType.ADD && e.estado().coerente()
                        && !e.estado().getOcorridoEm().isAfter(fim)
                        && historico.getUnidadeChave().equals(e.estado().getUnidadeChave()))
                && conferir(historico, revisoes, eventos).comprovada();
    }

    private static List<Revisao<VinculoAssistencial>> revisoesVinculo(AuditReader leitor, Long id, Number corte) {
        var q = leitor.createQuery().forRevisionsOfEntity(VinculoAssistencial.class, false, true)
                .add(AuditEntity.id().eq(id)).addOrder(AuditEntity.revisionNumber().asc());
        if (corte != null) q.add(AuditEntity.revisionNumber().le(corte));
        List<?> linhas = q.getResultList();
        return linhas.stream().map(row -> revisao(row, VinculoAssistencial.class)).toList();
    }

    private static List<Revisao<EventoEstadoClinicoVinculo>> revisoesEventos(AuditReader leitor, Long id, Number corte) {
        var q = leitor.createQuery().forRevisionsOfEntity(EventoEstadoClinicoVinculo.class, false, corte != null)
                .add(AuditEntity.relatedId("vinculo").eq(id)).addOrder(AuditEntity.id().asc());
        if (corte != null) q.add(AuditEntity.revisionNumber().le(corte));
        List<?> linhas = q.getResultList();
        return linhas.stream().map(row -> revisao(row, EventoEstadoClinicoVinculo.class)).toList();
    }

    private static Situacao conferir(VinculoAssistencial vinculo,
            List<Revisao<VinculoAssistencial>> revisoes, List<Revisao<EventoEstadoClinicoVinculo>> eventos) {
        if (revisoes.isEmpty())
            return eventos.isEmpty() ? semComprovacao(vinculo) : Situacao.CONFLITANTE;

        var porRevisao = eventos.stream()
                .collect(Collectors.groupingBy(Revisao::numero));
        StatusPaciente anterior = null;
        boolean houveRevisao = false;
        boolean possuiEvento = false;
        var situacao = semComprovacao(vinculo);
        for (var item : revisoes) {
            var estado = item.estado();
            if (item.tipo() == RevisionType.DEL || !vinculo.getUnidadeChave().equals(estado.getUnidadeChave())
                    || !vinculo.getIdPublico().equals(estado.getIdPublico())
                    || estado.getPaciente() == null
                    || !vinculo.getPaciente().getId().equals(estado.getPaciente().getId()))
                return Situacao.CONFLITANTE;
            var naRevisao = porRevisao.remove(item.numero());
            if (naRevisao != null) {
                for (var registro : naRevisao) {
                    var evento = registro.estado();
                    if (evento.getNatureza() == Natureza.INICIALIZACAO) {
                        if (houveRevisao || possuiEvento || item.tipo() != RevisionType.ADD
                                || !estadoInicialCoerente(estado))
                            return Situacao.CONFLITANTE;
                        situacao = Situacao.INICIALIZACAO_AUDITADA;
                    } else {
                        // Vínculos antigos podem não ter revisão de criação. O primeiro
                        // comando estruturado ainda pode comprovar o estado resultante.
                        if ((!houveRevisao && !possuiEvento)
                                ? item.tipo() != RevisionType.MOD
                                : evento.getStatusAnterior() != anterior)
                            return Situacao.CONFLITANTE;
                        situacao = Situacao.TRANSICAO_AUDITADA;
                    }
                    anterior = evento.getStatusResultante();
                    possuiEvento = true;
                }
                if (anterior != estado.getStatusPaciente()) return Situacao.CONFLITANTE;
            } else if (estado.getStatusPaciente() != anterior && houveRevisao) {
                if (possuiEvento) return Situacao.CONFLITANTE;
                situacao = Situacao.TRANSICAO_AUDITADA;
            }
            anterior = estado.getStatusPaciente();
            houveRevisao = true;
        }
        if (!porRevisao.isEmpty() || anterior != vinculo.getStatusPaciente()) return Situacao.CONFLITANTE;
        return anterior == null ? Situacao.AUSENTE : situacao;
    }

    private static Situacao semComprovacao(VinculoAssistencial vinculo) {
        return vinculo.getStatusPaciente() == null ? Situacao.AUSENTE : Situacao.SEM_COMPROVACAO;
    }

    private static boolean estadoInicialCoerente(VinculoAssistencial estado) {
        return estado.getStatusPaciente() == StatusPaciente.ATIVO
                && estado.getTipoAcompanhamento() != null
                && Integer.valueOf(0).equals(estado.getCountFaltas())
                && estado.getDataUltimaPresenca() == null
                && Boolean.FALSE.equals(estado.getGatilhoVisitaAcionado())
                && Boolean.FALSE.equals(estado.getIndicadoresPendentes())
                && estado.getClassificacaoRisco() != null;
    }

    private static boolean mesmoEvento(EventoEstadoClinicoVinculo atual, EventoEstadoClinicoVinculo auditado,
            VinculoAssistencial vinculo) {
        return Objects.equals(atual.getId(), auditado.getId())
                && vinculo.getUnidadeChave().equals(auditado.getUnidadeChave())
                && Objects.equals(atual.getUnidadeChave(), auditado.getUnidadeChave())
                && auditado.coerente()
                && Objects.equals(atual.getAtor().getId(), auditado.getAtor().getId())
                && Objects.equals(atual.getOcorridoEm(), auditado.getOcorridoEm())
                && atual.getNatureza() == auditado.getNatureza()
                && atual.getStatusAnterior() == auditado.getStatusAnterior()
                && atual.getStatusResultante() == auditado.getStatusResultante()
                && Objects.equals(atual.getMotivo(), auditado.getMotivo())
                && Objects.equals(atual.getDescricao(), auditado.getDescricao());
    }

    private static <T> Revisao<T> revisao(Object linha, Class<T> tipo) {
        var colunas = (Object[]) linha;
        return new Revisao<>(tipo.cast(colunas[0]), ((DefaultRevisionEntity) colunas[1]).getId(),
                (RevisionType) colunas[2]);
    }
}
