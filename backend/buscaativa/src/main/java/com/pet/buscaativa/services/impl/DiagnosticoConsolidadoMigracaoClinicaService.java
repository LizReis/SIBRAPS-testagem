package com.pet.buscaativa.services.impl;

import static com.pet.buscaativa.services.impl.AvaliacaoProvenienciaClinica.Situacao.COMPROVADA;
import static com.pet.buscaativa.services.impl.AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE;
import static com.pet.buscaativa.services.impl.AvaliacaoProvenienciaClinica.Situacao.SEM_ORIGEM_COMPROVADA;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.migration.CadastroUnicoPreflight;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Evidencia;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Fonte;
import com.pet.buscaativa.services.impl.AvaliacaoProvenienciaClinica.Situacao;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Inventário agregado para ensaios internos sobre H2 sintético ou cópia isolada V13. */
@Service
@Profile("diagnostico-migracao")
@RequiredArgsConstructor
public class DiagnosticoConsolidadoMigracaoClinicaService {
    private final EntityManager em;
    private final DataSource fonte;
    private final AgendamentoRepository agendaRepositorio;
    private final LinhaDoTempoFrequenciasVinculoService linhaDoTempo;

    public enum TipoRegistro { AGENDAMENTO, GRUPO, SESSAO, PARTICIPACAO, HISTORICO }
    public enum ProntidaoTecnica { REQUER_CONFERENCIA, COERENCIA_APENAS_PARA_TESTES }

    public record Proveniencia(long comprovados, long semOrigemComprovada, long inconsistentes) {}

    public record Relatorio(
            CadastroUnicoPreflight.Relatorio cadastro,
            Map<TipoRegistro, Proveniencia> proveniencia,
            Map<DiagnosticoProntidaoEstadoVinculoService.Situacao, Long> prontidaoPorVinculo,
            long vinculosInconsistentes,
            long frequenciasExcluidasDoEnsaio) {

        public ProntidaoTecnica prontidaoTecnica() {
            if (cadastro.documentosCpfDuplicados() > 0 || cadastro.documentosCnsDuplicados() > 0
                    || cadastro.pacientesSemCpf() > 0 || cadastro.pacientesSemCns() > 0
                    || vinculosInconsistentes > 0 || frequenciasExcluidasDoEnsaio > 0
                    || proveniencia.values().stream().anyMatch(p -> p.semOrigemComprovada() > 0 || p.inconsistentes() > 0)
                    || prontidaoPorVinculo.keySet().stream().anyMatch(
                            s -> s != DiagnosticoProntidaoEstadoVinculoService.Situacao.COERENTE_APENAS_PARA_TESTES)) {
                return ProntidaoTecnica.REQUER_CONFERENCIA;
            }
            return ProntidaoTecnica.COERENCIA_APENAS_PARA_TESTES;
        }

        public boolean migracaoAutorizada() {
            return false;
        }
    }

    private record ChaveVinculo(Long paciente, String unidade) {}

    /** Executar em transação própria de leitura, sem requisição HTTP ou operação assistencial. */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRES_NEW)
    public Relatorio analisar() {
        var cadastro = CadastroUnicoPreflight.analisar(fonte);
        var unidades = new HashSet<>(Set.of("CAPS_AD", "CAPS_I", "CAPS_II"));
        em.createQuery("select u from UsfReferencia u", UsfReferencia.class)
                .getResultList().stream().map(ChaveUnidadeAssistencial::daUsf).forEach(unidades::add);

        Set<ChaveVinculo> vinculosValidos = new HashSet<>();
        var estados = new EnumMap<DiagnosticoProntidaoEstadoVinculoService.Situacao, Long>(
                DiagnosticoProntidaoEstadoVinculoService.Situacao.class);
        long vinculosInvalidos = 0;
        try (var resultados = em.createQuery("select v from VinculoAssistencial v", VinculoAssistencial.class)
                .getResultStream()) {
            for (var iterador = resultados.iterator(); iterador.hasNext();) {
                var vinculo = iterador.next();
                if (!vinculoIntegro(vinculo, unidades)
                        || !vinculosValidos.add(new ChaveVinculo(vinculo.getPaciente().getId(), vinculo.getUnidadeChave()))) {
                    vinculosInvalidos++;
                    continue;
                }
                var faltas = DiagnosticoFaltasConsecutivasVinculoService.calcular(linhaDoTempo.analisar(vinculo));
                var estado = DiagnosticoProntidaoEstadoVinculoService.avaliar(vinculo, faltas);
                estados.merge(estado.situacao(), 1L, Long::sum);
            }
        }

        var contagem = new EnumMap<TipoRegistro, Map<Situacao, Long>>(TipoRegistro.class);
        for (var tipo : TipoRegistro.values()) contagem.put(tipo, new EnumMap<>(Situacao.class));

        long excluidas = 0;
        try (var resultados = em.createQuery("select a from Agendamento a", Agendamento.class).getResultStream()) {
            for (var iterador = resultados.iterator(); iterador.hasNext();) {
                var agenda = iterador.next();
                var situacao = origemAgendamento(agenda, vinculosValidos);
                registrar(contagem, TipoRegistro.AGENDAMENTO, situacao);
                if (AvaliacaoProvenienciaClinica.frequenciaIndividualEmAnalise(agenda)
                        && (situacao != COMPROVADA || !AvaliacaoProvenienciaClinica.frequenciaIndividualRegistrada(agenda))) {
                    excluidas++;
                }
            }
        }

        try (var origens = em.createQuery("select g.unidadeOrigemChave from GrupoTerapeutico g", String.class)
                .getResultStream()) {
            origens.forEach(origem -> registrar(contagem, TipoRegistro.GRUPO, unidadeGravada(origem, unidades)));
        }
        try (var sessoes = em.createQuery("select s from SessaoGrupo s", SessaoGrupo.class).getResultStream()) {
            sessoes.forEach(sessao -> registrar(contagem, TipoRegistro.SESSAO, origemSessao(sessao, unidades)));
        }
        try (var resultados = em.createQuery("""
                select p from SessaoGrupoParticipante p
                join fetch p.sessaoGrupo s
                join fetch s.grupo
                """, SessaoGrupoParticipante.class).getResultStream()) {
            for (var iterador = resultados.iterator(); iterador.hasNext();) {
                var participacao = iterador.next();
                var sessao = participacao.getSessaoGrupo();
                var situacao = origemSessao(sessao, unidades);
                boolean frequenciaEmAnalise = sessao.getStatus() == StatusSessaoGrupo.REALIZADA
                        || sessao.getStatus() == null
                        || participacao.getStatusPresenca() != StatusPresencaGrupo.NAO_REGISTRADA;
                if (situacao == COMPROVADA
                        && (!vinculosValidos.contains(new ChaveVinculo(participacao.getPaciente().getId(),
                                sessao.getUnidadeOrigemChave()))
                            || frequenciaEmAnalise && (sessao.getStatus() != StatusSessaoGrupo.REALIZADA
                                || !AvaliacaoProvenienciaClinica.frequenciaRegistrada(participacao.getStatusPresenca())))) {
                    situacao = INCONSISTENTE;
                }
                registrar(contagem, TipoRegistro.PARTICIPACAO, situacao);
                if (frequenciaEmAnalise && situacao != COMPROVADA) excluidas++;
            }
        }
        try (var historicos = em.createQuery("select h from HistoricoPaciente h", HistoricoPaciente.class)
                .getResultStream()) {
            historicos.forEach(historico -> registrar(contagem, TipoRegistro.HISTORICO,
                    origemHistorico(historico, unidades, vinculosValidos)));
        }

        var proveniencia = new EnumMap<TipoRegistro, Proveniencia>(TipoRegistro.class);
        contagem.forEach((tipo, totais) -> proveniencia.put(tipo, new Proveniencia(
                totais.getOrDefault(COMPROVADA, 0L),
                totais.getOrDefault(SEM_ORIGEM_COMPROVADA, 0L),
                totais.getOrDefault(INCONSISTENTE, 0L))));
        return new Relatorio(cadastro, Map.copyOf(proveniencia), Map.copyOf(estados), vinculosInvalidos, excluidas);
    }

    private static boolean vinculoIntegro(VinculoAssistencial vinculo, Set<String> unidades) {
        if (vinculo.getPaciente() == null || !unidades.contains(vinculo.getUnidadeChave())
                || vinculo.getUnidadeAtuacao() == null) return false;
        try {
            return vinculo.getUnidadeChave().equals(
                    ChaveUnidadeAssistencial.doVinculo(vinculo.getUnidadeAtuacao(), vinculo.getUsfAtuacao()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Situacao unidadeGravada(String chave, Set<String> unidades) {
        if (AvaliacaoProvenienciaClinica.origemAusente(chave)) return SEM_ORIGEM_COMPROVADA;
        return unidades.contains(chave) ? COMPROVADA : INCONSISTENTE;
    }

    private Situacao origemAgendamento(Agendamento agenda, Set<ChaveVinculo> vinculos) {
        String unidade = agenda.getUnidadeOrigemChave();
        if (AvaliacaoProvenienciaClinica.origemAusente(unidade)) return SEM_ORIGEM_COMPROVADA;
        Long paciente = agenda.getPaciente().getId();
        if (!vinculos.contains(new ChaveVinculo(paciente, unidade))) return INCONSISTENTE;
        return AvaliacaoProvenienciaClinica.cadeiaAgendamento(agenda, paciente, unidade,
                (original, sucessor) -> original.getId() == null || sucessor.getId() == null
                        || agendaRepositorio.existsByAgendamentoOriginal_IdAndIdNot(
                                original.getId(), sucessor.getId()));
    }

    private static Situacao origemSessao(SessaoGrupo sessao, Set<String> unidades) {
        var situacao = AvaliacaoProvenienciaClinica.origemSessao(sessao);
        if (situacao != COMPROVADA) return situacao;
        if (sessao.getStatus() == StatusSessaoGrupo.REALIZADA && sessao.getParticipantes().isEmpty())
            return INCONSISTENTE;
        return unidadeGravada(sessao.getUnidadeOrigemChave(), unidades);
    }

    private Situacao origemHistorico(HistoricoPaciente h, Set<String> unidades,
            Set<ChaveVinculo> vinculos) {
        String unidade = h.getUnidadeOrigemChave();
        Long paciente = h.getPaciente().getId();
        if (AvaliacaoProvenienciaClinica.origemAusente(unidade)) return SEM_ORIGEM_COMPROVADA;
        if (!vinculos.contains(new ChaveVinculo(paciente, unidade)) || h.getOcorridoEm() == null
                || h.getAgendamento() != null && h.getSessaoGrupo() != null) return INCONSISTENTE;

        if (h.getAgendamento() != null) {
            var agenda = h.getAgendamento();
            var fonte = classificarFonte(unidade, Fonte.AGENDAMENTO, agenda.getUnidadeOrigemChave(),
                    Objects.equals(paciente, agenda.getPaciente().getId()));
            return fonte == COMPROVADA ? origemAgendamento(agenda, vinculos) : fonte;
        }
        if (h.getSessaoGrupo() != null) {
            var sessao = h.getSessaoGrupo();
            boolean participou = sessao.getParticipantes().stream()
                    .anyMatch(p -> Objects.equals(paciente, p.getPaciente().getId()));
            var fonte = classificarFonte(unidade, Fonte.SESSAO_GRUPO, sessao.getUnidadeOrigemChave(),
                    participou && sessao.getStatus() == StatusSessaoGrupo.REALIZADA);
            return fonte == COMPROVADA ? origemSessao(sessao, unidades) : fonte;
        }

        return AvaliacaoProvenienciaClinica.acaoLocalSemFonteRegistrada(h) ? COMPROVADA : INCONSISTENTE;
    }

    private static Situacao classificarFonte(String unidade, Fonte fonte, String origemFonte, boolean integridade) {
        return switch (ClassificadorEvidenciaClinicaLocal.classificar(
                unidade, new Evidencia(fonte, unidade, origemFonte, integridade, true))) {
            case COMPATIVEL_SOMENTE_PARA_CONFERENCIA -> COMPROVADA;
            case REVISAR_ORIGEM_DESCONHECIDA, REVISAR_FONTE_SEM_ORIGEM -> SEM_ORIGEM_COMPROVADA;
            default -> INCONSISTENTE;
        };
    }

    private static void registrar(Map<TipoRegistro, Map<Situacao, Long>> contagem, TipoRegistro tipo, Situacao situacao) {
        contagem.get(tipo).merge(situacao, 1L, Long::sum);
    }
}
