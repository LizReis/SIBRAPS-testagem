package com.pet.buscaativa.services.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.SituacaoReserva;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/** Projeção interna de um vínculo autorizado a partir de um único snapshot H2. */
@Service
@Profile("diagnostico-migracao")
@RequiredArgsConstructor
public class ProjecaoClinicaVinculoService {
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final CalculoIndicadoresVinculo calculoLocal;
    private final HistoricoPacienteRepository historicos;
    private final EvidenciaStatusVinculo evidenciaStatus;

    public enum Motivo {
        ORIGEM_HISTORICA_INDETERMINADA, FREQUENCIA_INCONSISTENTE, ORDEM_DE_FREQUENCIAS_INDETERMINADA,
        CADEIA_LOCAL_INCONCLUSIVA, STATUS_AUSENTE, STATUS_SEM_TRANSICAO_AUDITADA, STATUS_EVIDENCIA_CONFLITANTE,
        TIPO_AUSENTE, PENDENCIA_AUSENTE, INDICADORES_PENDENTES
    }

    public enum Comparacao { COINCIDE, DIVERGE, SEM_REFERENCIA, INCONCLUSIVA }

    /** Uma avaliação conclusiva da última presença pode comprovar sua ausência. */
    public record Avaliacao<T>(Optional<T> valor, Set<Motivo> motivos,
            Comparacao estadoLocalArmazenado, Comparacao referenciaGlobal) {
        public boolean conclusiva() {
            return motivos.isEmpty();
        }
    }

    public record Reserva(Long id, LocalDate data, LocalTime hora,
            SituacaoReserva situacao, FrequenciaAssistencial frequencia) {}

    public record Participacao(Long id, Long sessaoId, Long grupoId, LocalDate data, LocalTime hora,
            StatusSessaoGrupo situacao, Optional<StatusPresencaGrupo> frequencia) {}

    public record EventoHistorico(Long id, LocalDateTime ocorridoEm, TipoEventoHistoricoPaciente tipo,
            FonteHistorico fonte, Long fonteId) {}

    public enum FonteHistorico { AGENDAMENTO, SESSAO, ACAO_LOCAL }

    public record Projecao(UUID vinculo, String unidade, LocalDate dataReferencia,
            List<Reserva> agenda, List<Participacao> grupos, List<EventoHistorico> historico,
            LinhaDoTempoFrequenciasVinculoService.Diagnostico frequencias,
            InventarioFrequenciasVinculoService.Inventario inventario,
            DiagnosticoProntidaoEstadoVinculoService.Relatorio prontidao,
            EvidenciaStatusVinculo.Situacao evidenciaStatus, Avaliacao<StatusPaciente> status, Avaliacao<TipoAcompanhamento> acompanhamento,
            Avaliacao<Boolean> pendencias, Avaliacao<Integer> faltas, Avaliacao<LocalDate> ultimaPresenca,
            Avaliacao<ClassificacaoRisco> risco, Avaliacao<Boolean> visita,
            Avaliacao<ComparadorSelecaoBuscaAtivaVinculo.Resultado> buscaAtiva) {}

    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRES_NEW)
    public Projecao projetar(UUID pacienteId, UUID vinculoId, LocalDate dataReferencia) {
        Objects.requireNonNull(dataReferencia, "Data de referência obrigatória.");
        var vinculo = autorizacao.exigirVinculoIdentificado(pacienteId, vinculoId);
        String unidade = vinculo.getUnidadeChave();
        if (!chaveCanonica(vinculo)) {
            throw new ResourceNotFoundException("Vínculo não encontrado.");
        }

        var provaStatus = evidenciaStatus.analisar(vinculo);
        var indicadores = calculoLocal.calcular(vinculo, dataReferencia, provaStatus, vinculo.getIndicadoresPendentes());
        var evidencias = indicadores.evidencias();
        var calculo = indicadores.frequencias();
        var frequencias = evidencias.diagnostico();
        var busca = indicadores.busca();
        var agenda = evidencias.agenda().stream().map(a -> new Reserva(a.getId(), a.getDataAgendamento(),
                a.getHoraAtendimento(), a.getSituacaoReserva(), a.getFrequenciaAssistencial()))
                .sorted(ordem(Reserva::data, Reserva::hora, Reserva::id)).toList();
        var grupos = evidencias.participacoes().stream().map(ProjecaoClinicaVinculoService::projetarParticipacao)
                .sorted(ordem(Participacao::data, Participacao::hora, Participacao::id)).toList();
        var historico = projetarHistorico(pacienteId, unidade, evidencias);

        // A comparação é posterior à composição. Apenas o sinal da comparação sai do vínculo.
        var global = vinculo.getPaciente();
        boolean globalElegivel = global.getStatusPaciente() == StatusPaciente.ATIVO
                && (global.getClassificacaoRisco() == ClassificacaoRisco.VERMELHO
                    || Boolean.TRUE.equals(global.getIndicadoresPendentes())
                    || Boolean.TRUE.equals(global.getGatilhoVisitaAcionado()));
        var buscaComparada = new Avaliacao<>(Optional.of(busca), Set.copyOf(indicadores.causasBusca()), Comparacao.SEM_REFERENCIA,
                comparar(busca.decisao() == ComparadorSelecaoBuscaAtivaVinculo.Decisao.INCLUIR,
                        global.getStatusPaciente() == null ? null : globalElegivel, indicadores.causasBusca()));

        return new Projecao(vinculoId, unidade, dataReferencia, agenda, grupos, historico, frequencias,
                InventarioFrequenciasVinculoService.inventariar(frequencias),
                DiagnosticoProntidaoEstadoVinculoService.avaliar(vinculo, calculo, provaStatus.comprovada()),
                provaStatus,
                comparar(indicadores.status(), vinculo.getStatusPaciente(), global.getStatusPaciente()),
                comparar(indicadores.tipo(), indicadores.tipo().valor().orElse(null), global.getTipoAcompanhamento()),
                comparar(indicadores.pendencia(), indicadores.pendencia().valor().orElse(null), global.getIndicadoresPendentes()),
                comparar(indicadores.faltas(), vinculo.getCountFaltas(), global.getCountFaltas()),
                comparar(indicadores.ultimaPresenca(), vinculo.getDataUltimaPresenca(), global.getDataUltimaPresenca()),
                comparar(indicadores.risco(), vinculo.getClassificacaoRisco(), global.getClassificacaoRisco()),
                comparar(indicadores.visita(), vinculo.getGatilhoVisitaAcionado(), global.getGatilhoVisitaAcionado()),
                buscaComparada);
    }

    static boolean chaveCanonica(VinculoAssistencial vinculo) {
        if (vinculo.getUnidadeAtuacao() == null) return false;
        try {
            return Objects.equals(vinculo.getUnidadeChave(),
                    ChaveUnidadeAssistencial.doVinculo(vinculo.getUnidadeAtuacao(), vinculo.getUsfAtuacao()));
        } catch (IllegalArgumentException incoerente) {
            return false;
        }
    }

    private List<EventoHistorico> projetarHistorico(UUID paciente, String unidade,
            LinhaDoTempoFrequenciasVinculoService.Evidencias evidencias) {
        var agendas = evidencias.agenda().stream().map(Agendamento::getId).collect(Collectors.toSet());
        var sessoes = evidencias.participacoes().stream().filter(p ->
                    p.getSessaoGrupo().getStatus() == StatusSessaoGrupo.REALIZADA
                    && AvaliacaoProvenienciaClinica.frequenciaRegistrada(p.getStatusPresenca()))
                .map(p -> p.getSessaoGrupo().getId()).collect(Collectors.toSet());
        return historicos.findLocaisComOrigemComprovada(paciente, unidade).stream()
                .map(h -> projetarEvento(h, agendas, sessoes))
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<EventoHistorico> projetarEvento(HistoricoPaciente h, Set<Long> agendas, Set<Long> sessoes) {
        if (h.getAgendamento() != null && h.getSessaoGrupo() != null) return Optional.empty();
        if (h.getAgendamento() != null) {
            Long fonte = h.getAgendamento().getId();
            return agendas.contains(fonte)
                    ? Optional.of(new EventoHistorico(h.getId(), h.getOcorridoEm(), h.getTipo(), FonteHistorico.AGENDAMENTO, fonte))
                    : Optional.empty();
        }
        if (h.getSessaoGrupo() != null) {
            Long fonte = h.getSessaoGrupo().getId();
            return sessoes.contains(fonte)
                    ? Optional.of(new EventoHistorico(h.getId(), h.getOcorridoEm(), h.getTipo(), FonteHistorico.SESSAO, fonte))
                    : Optional.empty();
        }
        return AvaliacaoProvenienciaClinica.acaoLocalSemFonteRegistrada(h)
                ? Optional.of(new EventoHistorico(h.getId(), h.getOcorridoEm(), h.getTipo(), FonteHistorico.ACAO_LOCAL, null))
                : Optional.empty();
    }

    private static Participacao projetarParticipacao(SessaoGrupoParticipante p) {
        var s = p.getSessaoGrupo();
        var frequencia = s.getStatus() == StatusSessaoGrupo.REALIZADA
                && AvaliacaoProvenienciaClinica.frequenciaRegistrada(p.getStatusPresenca())
                ? Optional.of(p.getStatusPresenca()) : Optional.<StatusPresencaGrupo>empty();
        return new Participacao(p.getId(), s.getId(), s.getGrupo().getId(), s.getDataSessao(), s.getHorario(),
                s.getStatus(), frequencia);
    }

    private static <T> Comparator<T> ordem(Function<T, LocalDate> data, Function<T, LocalTime> hora,
            Function<T, Long> id) {
        return Comparator.comparing(data, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(hora, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(id);
    }

    private static <T> Avaliacao<T> comparar(Avaliacao<T> avaliacao, T local, T global) {
        var valor = avaliacao.valor().orElse(null);
        return new Avaliacao<>(avaliacao.valor(), avaliacao.motivos(),
                comparar(valor, local, avaliacao.motivos()), comparar(valor, global, avaliacao.motivos()));
    }

    private static Comparacao comparar(Object valor, Object referencia, Set<Motivo> motivos) {
        if (!motivos.isEmpty()) return Comparacao.INCONCLUSIVA;
        if (referencia == null) return Comparacao.SEM_REFERENCIA;
        return Objects.equals(valor, referencia) ? Comparacao.COINCIDE : Comparacao.DIVERGE;
    }
}
