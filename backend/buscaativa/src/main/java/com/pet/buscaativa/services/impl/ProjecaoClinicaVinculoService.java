package com.pet.buscaativa.services.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.query.AuditEntity;
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
import com.pet.buscaativa.services.PoliticaVisitaDomiciliar;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Projeção interna de um vínculo autorizado a partir de um único snapshot H2. */
@Service
@Profile("diagnostico-migracao")
@RequiredArgsConstructor
public class ProjecaoClinicaVinculoService {
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final LinhaDoTempoFrequenciasVinculoService linhaDoTempo;
    private final HistoricoPacienteRepository historicos;
    private final PoliticaVisitaDomiciliar politicaVisita;
    private final EntityManager em;

    public enum Motivo {
        ORIGEM_HISTORICA_INDETERMINADA, FREQUENCIA_INCONSISTENTE, ORDEM_DE_FREQUENCIAS_INDETERMINADA,
        CADEIA_LOCAL_INCONCLUSIVA, STATUS_AUSENTE, STATUS_SEM_TRANSICAO_AUDITADA,
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
            Avaliacao<StatusPaciente> status, Avaliacao<TipoAcompanhamento> acompanhamento,
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

        var evidencias = linhaDoTempo.examinar(vinculo, true);
        var frequencias = evidencias.diagnostico();
        var calculo = DiagnosticoFaltasConsecutivasVinculoService.calcular(frequencias);
        var causasSequencia = motivosSequencia(calculo);
        var causasStatus = EnumSet.noneOf(Motivo.class);
        if (vinculo.getStatusPaciente() == null) {
            causasStatus.add(Motivo.STATUS_AUSENTE);
        } else if (!statusTemTransicaoAuditada(vinculo)) {
            causasStatus.add(Motivo.STATUS_SEM_TRANSICAO_AUDITADA);
        }
        StatusPaciente status = causasStatus.isEmpty() ? vinculo.getStatusPaciente() : null;
        var tipo = vinculo.getTipoAcompanhamento();
        Boolean pendencia = vinculo.getIndicadoresPendentes();
        var causasTipo = tipo == null ? EnumSet.of(Motivo.TIPO_AUSENTE) : EnumSet.noneOf(Motivo.class);
        var causasPendencia = pendencia == null
                ? EnumSet.of(Motivo.PENDENCIA_AUSENTE) : EnumSet.noneOf(Motivo.class);
        var causasVisita = EnumSet.copyOf(causasSequencia);
        causasVisita.addAll(causasPendencia);
        if (Boolean.TRUE.equals(pendencia)) causasVisita.add(Motivo.INDICADORES_PENDENTES);

        var causasRisco = EnumSet.copyOf(causasStatus);
        Optional<ClassificacaoRisco> risco;
        if (status == StatusPaciente.INATIVO) {
            // A regra legada encerra a avaliação de risco pelo estado inativo.
            risco = Optional.of(ClassificacaoRisco.VERDE);
        } else {
            causasRisco.addAll(causasVisita);
            causasRisco.addAll(causasTipo);
            risco = causasRisco.isEmpty()
                    ? ComparadorPoliticaRiscoVinculo.comparar(calculo, status, tipo, pendencia, dataReferencia)
                    : Optional.empty();
        }
        if (evidencias.agendaLocalInconclusiva()) causasVisita.add(Motivo.CADEIA_LOCAL_INCONCLUSIVA);
        var visita = causasVisita.isEmpty()
                ? ComparadorPoliticaVisitaVinculo.compararAgendaComprovada(evidencias.agenda(), calculo, politicaVisita)
                : Optional.<Boolean>empty();

        var busca = ComparadorSelecaoBuscaAtivaVinculo.comparar(status, risco, pendencia, visita);
        var causasBusca = EnumSet.noneOf(Motivo.class);
        if (busca.decisao() == ComparadorSelecaoBuscaAtivaVinculo.Decisao.INDETERMINADA) {
            causasBusca.addAll(causasRisco);
            causasBusca.addAll(causasVisita);
        }

        var agenda = evidencias.agenda().stream().map(a -> new Reserva(a.getId(), a.getDataAgendamento(),
                a.getHoraAtendimento(), a.getSituacaoReserva(), a.getFrequenciaAssistencial()))
                .sorted(ordem(Reserva::data, Reserva::hora, Reserva::id)).toList();
        var grupos = evidencias.participacoes().stream().map(ProjecaoClinicaVinculoService::projetarParticipacao)
                .sorted(ordem(Participacao::data, Participacao::hora, Participacao::id)).toList();
        var historico = projetarHistorico(pacienteId, unidade, evidencias);
        Integer faltas = calculo.faltasConsecutivasAnalisaveis().isPresent()
                ? calculo.faltasConsecutivasAnalisaveis().getAsInt() : null;
        var ultima = calculo.ultimaPresencaComprovada().map(LocalDateTime::toLocalDate).orElse(null);

        // A comparação é posterior à composição. Apenas o sinal da comparação sai do vínculo.
        var global = vinculo.getPaciente();
        boolean globalElegivel = global.getStatusPaciente() == StatusPaciente.ATIVO
                && (global.getClassificacaoRisco() == ClassificacaoRisco.VERMELHO
                    || Boolean.TRUE.equals(global.getIndicadoresPendentes())
                    || Boolean.TRUE.equals(global.getGatilhoVisitaAcionado()));
        var buscaComparada = new Avaliacao<>(Optional.of(busca), Set.copyOf(causasBusca), Comparacao.SEM_REFERENCIA,
                comparar(busca.decisao() == ComparadorSelecaoBuscaAtivaVinculo.Decisao.INCLUIR,
                        global.getStatusPaciente() == null ? null : globalElegivel, causasBusca));

        return new Projecao(vinculoId, unidade, dataReferencia, agenda, grupos, historico, frequencias,
                InventarioFrequenciasVinculoService.inventariar(frequencias),
                DiagnosticoProntidaoEstadoVinculoService.avaliar(vinculo, calculo),
                avaliar(status, causasStatus, vinculo.getStatusPaciente(), global.getStatusPaciente()),
                avaliar(tipo, causasTipo, tipo, global.getTipoAcompanhamento()),
                avaliar(pendencia, causasPendencia, pendencia, global.getIndicadoresPendentes()),
                avaliar(faltas, causasSequencia, vinculo.getCountFaltas(), global.getCountFaltas()),
                avaliar(ultima, causasSequencia, vinculo.getDataUltimaPresenca(), global.getDataUltimaPresenca()),
                avaliar(risco.orElse(null), causasRisco, vinculo.getClassificacaoRisco(), global.getClassificacaoRisco()),
                avaliar(visita.orElse(null), causasVisita, vinculo.getGatilhoVisitaAcionado(), global.getGatilhoVisitaAcionado()),
                buscaComparada);
    }

    private boolean statusTemTransicaoAuditada(VinculoAssistencial vinculo) {
        List<?> valores = AuditReaderFactory.get(em).createQuery()
                .forRevisionsOfEntity(VinculoAssistencial.class, false, false)
                .add(AuditEntity.id().eq(vinculo.getId()))
                .addProjection(AuditEntity.property("statusPaciente"))
                .addOrder(AuditEntity.revisionNumber().desc())
                .getResultList();
        // O primeiro valor é o mais recente. O valor inicial ATIVO das fábricas não é prova.
        return !valores.isEmpty() && vinculo.getStatusPaciente().equals(valores.getFirst())
                && valores.stream().anyMatch(anterior -> !vinculo.getStatusPaciente().equals(anterior));
    }

    private static boolean chaveCanonica(VinculoAssistencial vinculo) {
        if (vinculo.getUnidadeAtuacao() == null) return false;
        try {
            return Objects.equals(vinculo.getUnidadeChave(),
                    ChaveUnidadeAssistencial.doVinculo(vinculo.getUnidadeAtuacao(), vinculo.getUsfAtuacao()));
        } catch (IllegalArgumentException incoerente) {
            return false;
        }
    }

    private static EnumSet<Motivo> motivosSequencia(DiagnosticoFaltasConsecutivasVinculoService.Resultado resultado) {
        var motivos = EnumSet.noneOf(Motivo.class);
        if (resultado.agendamentosSemOrigemComFrequencia() > 0 || resultado.presencasGrupoSemOrigem() > 0)
            motivos.add(Motivo.ORIGEM_HISTORICA_INDETERMINADA);
        if (resultado.frequenciasInconsistentes() > 0) motivos.add(Motivo.FREQUENCIA_INCONSISTENTE);
        if (resultado.empateComFrequenciasDiferentes()) motivos.add(Motivo.ORDEM_DE_FREQUENCIAS_INDETERMINADA);
        return motivos;
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

    private static <T> Avaliacao<T> avaliar(T valor, Set<Motivo> motivos, T local, T global) {
        return new Avaliacao<>(motivos.isEmpty() ? Optional.ofNullable(valor) : Optional.empty(),
                Set.copyOf(motivos), comparar(valor, local, motivos), comparar(valor, global, motivos));
    }

    private static Comparacao comparar(Object valor, Object referencia, Set<Motivo> motivos) {
        if (!motivos.isEmpty()) return Comparacao.INCONCLUSIVA;
        if (referencia == null) return Comparacao.SEM_REFERENCIA;
        return Objects.equals(valor, referencia) ? Comparacao.COINCIDE : Comparacao.DIVERGE;
    }
}
