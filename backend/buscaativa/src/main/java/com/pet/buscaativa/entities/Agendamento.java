package com.pet.buscaativa.entities;

import java.time.LocalDate;
import java.time.LocalTime;

import org.hibernate.envers.Audited;

import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TurnoEnum;
import com.pet.buscaativa.entities.enums.SituacaoReserva;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.AccessLevel;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Audited
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table(name = "tb_agendamento",uniqueConstraints=@jakarta.persistence.UniqueConstraint(name="uk_agendamento_identidade",columnNames={"id","paciente_id","usuario_id"}))
public class Agendamento {
    
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paciente_id", nullable = false)
    private Paciente paciente;


    private LocalDate dataAgendamento;

    @Convert(converter = TurnoEnumConverter.class)
    private TurnoEnum turnoAgendamento;

    private LocalTime horaAtendimento;

    @Convert(converter = SituacaoAtendimentoConverter.class)
    private SituacaoAtendimento situacaoAtendimento;

    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(name = "situacao_reserva", length = 20)
    @Setter(AccessLevel.NONE)
    private SituacaoReserva situacaoReserva;

    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(name = "frequencia_assistencial", length = 20)
    @Setter(AccessLevel.NONE)
    private FrequenciaAssistencial frequenciaAssistencial;

    @Column(name = "migracao_pendente")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean migracaoPendente = false;

    @Column(name = "motivo_migracao_pendente", length = 200)
    @Setter(AccessLevel.NONE)
    private String motivoMigracaoPendente;

    @Column(name = "ocupa_vaga")
    @Setter(AccessLevel.NONE)
    private Boolean ocupaVaga;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agendamento_original_id")
    private Agendamento agendamentoOriginal;

    @Column(name = "remarcacao_apos_falta", nullable = false)
    private boolean remarcacaoAposFalta;

    @Version
    private Integer version;

    public boolean isMigracaoPendente() { return Boolean.TRUE.equals(migracaoPendente); }

    public void setAgendamentoOriginal(Agendamento original) {
        if (original == this) throw new IllegalArgumentException("Vínculo de remarcação circular.");
        agendamentoOriginal = original;
        if (original != null && situacaoReserva == SituacaoReserva.AGENDADA) {
            situacaoReserva = SituacaoReserva.REMARCADA;
            sincronizarProjecoes();
        }
    }

    /** Compatibility command. JPA uses field access; loaded legacy values never become facts here. */
    public void setSituacaoAtendimento(SituacaoAtendimento comando) {
        if (isMigracaoPendente()) throw new IllegalStateException("Agendamento com migração pendente.");
        if (comando == null) throw new IllegalArgumentException("Situação obrigatória.");
        if (frequenciaAssistencial == null) frequenciaAssistencial = FrequenciaAssistencial.NAO_REGISTRADA;
        switch (comando) {
            case AGENDADO -> situacaoReserva = SituacaoReserva.AGENDADA;
            case REMARCADO -> situacaoReserva = SituacaoReserva.REMARCADA;
            case CANCELADO -> situacaoReserva = SituacaoReserva.CANCELADA;
            case REMARCADO_ORIGEM -> situacaoReserva = SituacaoReserva.SUBSTITUIDA;
            case PRESENTE, FALTOU -> {
                if (situacaoReserva == null) situacaoReserva = agendamentoOriginal == null
                        ? SituacaoReserva.AGENDADA : SituacaoReserva.REMARCADA;
                frequenciaAssistencial = comando == SituacaoAtendimento.PRESENTE
                        ? FrequenciaAssistencial.PRESENTE : FrequenciaAssistencial.FALTOU;
            }
        }
        sincronizarProjecoes();
    }

    public void recuperarReservaCancelada() {
        exigirCancelamentoSemFrequencia();
        situacaoReserva=agendamentoOriginal==null?SituacaoReserva.AGENDADA:SituacaoReserva.REMARCADA;
        sincronizarProjecoes();
    }

    public void registrarFatoEmReservaCancelada(FrequenciaAssistencial resultado) {
        exigirCancelamentoSemFrequencia();
        if(resultado!=FrequenciaAssistencial.PRESENTE && resultado!=FrequenciaAssistencial.FALTOU)
            throw new IllegalArgumentException("A regularização exige resultado assistencial explícito.");
        frequenciaAssistencial=resultado;
        sincronizarProjecoes();
    }

    private void exigirCancelamentoSemFrequencia() {
        if(isMigracaoPendente() || situacaoReserva!=SituacaoReserva.CANCELADA || frequenciaAssistencial!=FrequenciaAssistencial.NAO_REGISTRADA)
            throw new IllegalStateException("Regularização exige reserva cancelada e frequência identificada como não registrada.");
    }

    public SituacaoAtendimento getSituacaoAtendimento() {
        return isMigracaoPendente() || situacaoReserva == null || frequenciaAssistencial == null ? situacaoAtendimento
                : PoliticaOcupacaoAgendamento.projetar(situacaoReserva, frequenciaAssistencial);
    }

    @PrePersist @PreUpdate
    private void sincronizarProjecoes() {
        if (isMigracaoPendente()) return; // Unresolved migrated rows remain frozen at their observed legacy projection.
        if (situacaoReserva == null || frequenciaAssistencial == null) {
            if (situacaoAtendimento != null) throw new IllegalStateException("Estado canônico ausente.");
            migracaoPendente = true; motivoMigracaoPendente = "SITUACAO_LEGADA_AUSENTE"; ocupaVaga = false;
            return;
        }
        situacaoAtendimento = PoliticaOcupacaoAgendamento.projetar(situacaoReserva, frequenciaAssistencial);
        ocupaVaga = PoliticaOcupacaoAgendamento.ocupa(situacaoReserva, frequenciaAssistencial);
    }
}
