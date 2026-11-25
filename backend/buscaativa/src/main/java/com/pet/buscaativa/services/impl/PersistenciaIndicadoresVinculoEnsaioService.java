package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.migration.PlanoIndicadoresVinculo;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
public class PersistenciaIndicadoresVinculoEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final EvidenciaStatusVinculo evidencia;
    private final CalculoIndicadoresVinculo calculo;
    private final EntityManager em;
    private final Clock clock;

    public enum Situacao { ATUALIZADO, SEM_ALTERACAO, INCONCLUSIVO }
    public record Resultado(Situacao situacao, boolean indicacaoDePendenciaAlterada, Integer versao) {}
    record Avaliado(VinculoAssistencial vinculo, PlanoIndicadoresVinculo.Plano plano,
            boolean fatosInconclusivos, boolean apenasEmpate, boolean resolverEmpate, boolean encerradoComprovado) {}

    @Transactional
    public Resultado recalcular(UUID paciente, UUID vinculo, Integer versao) {
        var bloqueados = coordenacao.paraVinculo(atores.autorizar(false), paciente, vinculo, null);
        var local = bloqueados.vinculos().get(paciente);
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, local.getVersion());
        return aplicar(avaliar(local), bloqueados.atores().ator());
    }

    EvidenciaStatusVinculo.Situacao evidencia(VinculoAssistencial local) {
        return evidencia.analisar(local);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Avaliado avaliar(VinculoAssistencial local) {
        return avaliar(local, evidencia(local));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Avaliado avaliar(VinculoAssistencial local, EvidenciaStatusVinculo.Situacao prova) {
        return avaliar(local, prova, false);
    }

    /** Só antes de uma correção factual autorizada, após a aquisição de locks. */
    boolean empateConhecidoAntesDaCorrecao(VinculoAssistencial local) {
        if (!Boolean.TRUE.equals(local.getPendenciaEmpateLocal())) return false;
        var revisoes = org.hibernate.envers.AuditReaderFactory.get(em)
                .getRevisions(VinculoAssistencial.class, local.getId());
        if (revisoes.isEmpty()) return false;
        var auditado = org.hibernate.envers.AuditReaderFactory.get(em)
                .find(VinculoAssistencial.class, local.getId(), revisoes.getLast());
        return auditado != null && Boolean.TRUE.equals(auditado.getPendenciaEmpateLocal())
                && Boolean.TRUE.equals(auditado.getIndicadoresPendentes())
                && avaliar(local).apenasEmpate();
    }

    Avaliado avaliarAposCorrecao(VinculoAssistencial local, boolean empateAnterior) {
        return avaliar(local, evidencia(local), empateAnterior);
    }

    private Avaliado avaliar(VinculoAssistencial local, EvidenciaStatusVinculo.Situacao prova, boolean empateAnterior) {
        var data = LocalDate.now(clock);
        var valores = calculo.calcular(local, data, prova, local.getIndicadoresPendentes());
        boolean inconclusivo = !valores.faltas().conclusiva() || valores.evidencias().agendaLocalInconclusiva();
        var f = valores.frequencias();
        boolean apenasEmpate = prova.comprovada() && f.empateComFrequenciasDiferentes()
                && f.agendamentosSemOrigemComFrequencia() == 0 && f.presencasGrupoSemOrigem() == 0
                && f.frequenciasInconsistentes() == 0 && !valores.evidencias().agendaLocalInconclusiva();
        boolean podeResolver = empateAnterior && !inconclusivo && prova.comprovada()
                && Boolean.TRUE.equals(local.getPendenciaEmpateLocal()) && !possuiSolicitacaoRevisao(local);
        if (podeResolver) valores = calculo.calcular(local, data, prova, false);
        var plano = PlanoIndicadoresVinculo.preparar(local.getIdPublico(), local.getUnidadeChave(), data,
                valores.status(), valores.tipo(), valores.pendencia(), valores.faltas(),
                valores.ultimaPresenca(), valores.risco(), valores.visita());
        return new Avaliado(local, plano, inconclusivo, apenasEmpate,
                podeResolver && plano.candidato().isPresent(),
                valores.status().valor().filter(s -> s != com.pet.buscaativa.entities.enums.StatusPaciente.ATIVO).isPresent());
    }

    // V12/V13 não comprovam aplicação clínica. Barreira conservadora até o
    // futuro registro de aplicação: nenhuma solicitação local é descartada.
    private boolean possuiSolicitacaoRevisao(VinculoAssistencial local) {
        return em.createQuery("""
                select count(p) from PendenciaRevisaoProveniencia p
                where p.vinculo.id = :vinculo and p.pacienteId = :paciente and p.unidadeChave = :unidade
                """, Long.class).setParameter("vinculo", local.getId())
                .setParameter("paciente", local.getPaciente().getId())
                .setParameter("unidade", local.getUnidadeChave()).getSingleResult() > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Resultado aplicar(Avaliado avaliacao, Usuario ator) {
        java.util.Objects.requireNonNull(ator, "Ator humano obrigatório.");
        return aplicarInterno(avaliacao, ator);
    }

    /** Alterações derivadas auditadas pelo Envers, sem autoria clínica fictícia. */
    @Transactional(propagation = Propagation.MANDATORY)
    Resultado aplicarTecnico(Avaliado avaliacao) {
        if (org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() != null)
            throw new org.springframework.security.access.AccessDeniedException("Rotina técnica exige contexto sem autenticação.");
        if (avaliacao.resolverEmpate())
            throw new IllegalStateException("Rotina técnica não resolve pendência por correção.");
        return aplicarInterno(avaliacao, null);
    }

    private Resultado aplicarInterno(Avaliado avaliacao, Usuario ator) {
        var local = avaliacao.vinculo();
        var candidato = avaliacao.plano().candidato();
        if (candidato.isEmpty()) {
            boolean mudou = avaliacao.fatosInconclusivos() && (avaliacao.apenasEmpate()
                    ? local.sinalizarPendenciaDeEmpateLocal() : local.sinalizarIndicadoresLocaisPendentes());
            boolean efeitos = avaliacao.encerradoComprovado() && local.preservarEfeitosEncerramentoLocal();
            if (ator != null && (mudou || efeitos)) registrar(local, ator, "Indicadores locais inconclusivos; estado e pendência preservados conforme evidência.");
            return new Resultado(Situacao.INCONCLUSIVO, mudou, local.getVersion());
        }
        var valores = candidato.orElseThrow();
        if (avaliacao.resolverEmpate()) local.resolverPendenciaDeEmpateLocal();
        boolean mudou = local.aplicarIndicadoresLocais(valores.faltas(), valores.ultimaPresenca().orElse(null),
                valores.risco(), valores.gatilhoVisita());
        if (ator != null && (mudou || avaliacao.resolverEmpate())) registrar(local, ator, avaliacao.resolverEmpate()
                ? "Pendência de empate local resolvida por correção comprovada; indicadores recalculados."
                : "Indicadores do acompanhamento local atualizados.");
        return new Resultado(mudou || avaliacao.resolverEmpate() ? Situacao.ATUALIZADO : Situacao.SEM_ALTERACAO,
                avaliacao.resolverEmpate(), local.getVersion());
    }

    private void registrar(VinculoAssistencial local, Usuario ator, String descricao) {
        var h = new HistoricoPaciente();
        h.setPaciente(local.getPaciente());
        h.setProfissional(ator);
        h.setTipo(TipoEventoHistoricoPaciente.SITUACAO_ATUALIZADA);
        h.setOcorridoEm(LocalDateTime.now(clock));
        h.setDescricao(descricao);
        h.registrarOrigemDeAcaoLocal(ator);
        em.persist(h);
        em.flush();
    }
}
