package com.pet.buscaativa.services.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.PoliticaVisitaDomiciliar;
import com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Avaliacao;
import com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Comparacao;
import com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Motivo;

import lombok.RequiredArgsConstructor;

/** Cálculo comum ao diagnóstico e ao escritor, na transação do chamador. */
@Component
@RequiredArgsConstructor
class CalculoIndicadoresVinculo {
    private final LinhaDoTempoFrequenciasVinculoService linhaDoTempo;
    private final PoliticaVisitaDomiciliar politicaVisita;

    record Resultado(LinhaDoTempoFrequenciasVinculoService.Evidencias evidencias,
            DiagnosticoFaltasConsecutivasVinculoService.Resultado frequencias,
            Avaliacao<StatusPaciente> status, Avaliacao<TipoAcompanhamento> tipo,
            Avaliacao<Boolean> pendencia, Avaliacao<Integer> faltas, Avaliacao<LocalDate> ultimaPresenca,
            Avaliacao<ClassificacaoRisco> risco, Avaliacao<Boolean> visita,
            ComparadorSelecaoBuscaAtivaVinculo.Resultado busca, Set<Motivo> causasBusca) {}

    @Transactional(propagation = Propagation.MANDATORY)
    Resultado calcular(VinculoAssistencial vinculo, LocalDate dataReferencia,
            EvidenciaStatusVinculo.Situacao provaStatus, Boolean pendencia) {
        var evidencias = linhaDoTempo.examinarEfetivo(vinculo, true);
        var calculo = DiagnosticoFaltasConsecutivasVinculoService.calcular(evidencias.diagnostico());
        var causasSequencia = motivosSequencia(calculo);
        var causasStatus = EnumSet.noneOf(Motivo.class);
        switch (provaStatus) {
            case AUSENTE -> causasStatus.add(Motivo.STATUS_AUSENTE);
            case SEM_COMPROVACAO -> causasStatus.add(Motivo.STATUS_SEM_TRANSICAO_AUDITADA);
            case CONFLITANTE -> causasStatus.add(Motivo.STATUS_EVIDENCIA_CONFLITANTE);
            case INICIALIZACAO_AUDITADA, TRANSICAO_AUDITADA -> { }
        }
        StatusPaciente status = causasStatus.isEmpty() ? vinculo.getStatusPaciente() : null;
        var tipo = vinculo.getTipoAcompanhamento();
        var causasTipo = tipo == null ? EnumSet.of(Motivo.TIPO_AUSENTE) : EnumSet.noneOf(Motivo.class);
        var causasPendencia = pendencia == null
                ? EnumSet.of(Motivo.PENDENCIA_AUSENTE) : EnumSet.noneOf(Motivo.class);
        var causasVisita = EnumSet.copyOf(causasSequencia);
        causasVisita.addAll(causasPendencia);
        if (Boolean.TRUE.equals(pendencia)) causasVisita.add(Motivo.INDICADORES_PENDENTES);

        var causasRisco = EnumSet.copyOf(causasStatus);
        Optional<ClassificacaoRisco> risco;
        boolean encerradoComprovado = status != null && status != StatusPaciente.ATIVO;
        if (encerradoComprovado) {
            // Efeitos do encerramento explícito, não uma conclusão sobre faltas.
            risco = Optional.of(ClassificacaoRisco.VERDE);
        } else {
            causasRisco.addAll(causasVisita);
            causasRisco.addAll(causasTipo);
            risco = causasRisco.isEmpty()
                    ? ComparadorPoliticaRiscoVinculo.comparar(calculo, status, tipo, pendencia, dataReferencia)
                    : Optional.empty();
        }
        if (evidencias.agendaLocalInconclusiva()) causasVisita.add(Motivo.CADEIA_LOCAL_INCONCLUSIVA);
        Optional<Boolean> visita;
        if (encerradoComprovado) {
            causasVisita.clear();
            visita = Optional.of(false);
        } else {
            visita = causasVisita.isEmpty()
                    ? ComparadorPoliticaVisitaVinculo.compararAgendaComprovada(evidencias.agenda(), calculo, politicaVisita)
                    : Optional.empty();
        }

        var busca = ComparadorSelecaoBuscaAtivaVinculo.comparar(status, risco, pendencia, visita);
        var causasBusca = EnumSet.noneOf(Motivo.class);
        if (busca.decisao() == ComparadorSelecaoBuscaAtivaVinculo.Decisao.INDETERMINADA) {
            causasBusca.addAll(causasRisco);
            causasBusca.addAll(causasVisita);
        }

        Integer faltas = calculo.faltasConsecutivasAnalisaveis().isPresent()
                ? calculo.faltasConsecutivasAnalisaveis().getAsInt() : null;
        var ultima = calculo.ultimaPresencaComprovada().map(LocalDateTime::toLocalDate).orElse(null);
        return new Resultado(evidencias, calculo, avaliar(status, causasStatus), avaliar(tipo, causasTipo),
                avaliar(pendencia, causasPendencia), avaliar(faltas, causasSequencia), avaliar(ultima, causasSequencia),
                avaliar(risco.orElse(null), causasRisco), avaliar(visita.orElse(null), causasVisita), busca,
                Set.copyOf(causasBusca));
    }

    private static <T> Avaliacao<T> avaliar(T valor, Set<Motivo> motivos) {
        return new Avaliacao<>(motivos.isEmpty() ? Optional.ofNullable(valor) : Optional.empty(),
                Set.copyOf(motivos), Comparacao.SEM_REFERENCIA, Comparacao.SEM_REFERENCIA);
    }

    private static EnumSet<Motivo> motivosSequencia(DiagnosticoFaltasConsecutivasVinculoService.Resultado resultado) {
        var motivos = EnumSet.noneOf(Motivo.class);
        if (resultado.agendamentosSemOrigemComFrequencia() > 0 || resultado.presencasGrupoSemOrigem() > 0)
            motivos.add(Motivo.ORIGEM_HISTORICA_INDETERMINADA);
        if (resultado.frequenciasInconsistentes() > 0) motivos.add(Motivo.FREQUENCIA_INCONSISTENTE);
        if (resultado.empateComFrequenciasDiferentes()) motivos.add(Motivo.ORDEM_DE_FREQUENCIAS_INDETERMINADA);
        return motivos;
    }

 }
