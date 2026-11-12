package com.pet.buscaativa.entities;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;
import com.pet.buscaativa.entities.enums.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Administrative recovery or first factual registration, never a P/F correction. */
@Entity @jakarta.persistence.Table(name="tb_auditoria_regularizacao_cancelamento",indexes={
    @Index(name="ix_regularizacao_agendamento",columnList="agendamento_id,ocorrido_em,id")},
    uniqueConstraints=@UniqueConstraint(name="uk_regularizacao_versao",columnNames={"agendamento_id","versao_anterior"}))
@Immutable @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
@Check(name="ck_regularizacao_integridade",constraints="perfil in ('ADMINISTRADOR','PROFISSIONAL') and char_length(trim(unidade))>0 and reserva_anterior='CANCELADA' and frequencia_anterior='NAO_REGISTRADA' and char_length(trim(justificativa)) between 10 and 2000 and versao_anterior>=0 and versao_resultante=versao_anterior+1 and ((tipo_regularizacao='RECUPERACAO_RESERVA' and reserva_resultante in ('AGENDADA','REMARCADA') and frequencia_resultante='NAO_REGISTRADA') or (tipo_regularizacao='REGISTRO_FATO_ASSISTENCIAL' and reserva_resultante='CANCELADA' and frequencia_resultante in ('PRESENTE','FALTOU')))")
public class AuditoriaRegularizacaoCancelamento {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="agendamento_id",nullable=false) private Agendamento agendamento;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="paciente_id",nullable=false) private Paciente paciente;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="ator_id",nullable=false) private Usuario ator;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="responsavel_id",nullable=false) private Usuario responsavel;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,length=30) private TipoUsuario perfil;
    @Column(nullable=false,length=80) private String unidade;
    @Column(nullable=false,length=40) private String tipoRegularizacao;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,length=20) private SituacaoReserva reservaAnterior;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,length=20) private SituacaoReserva reservaResultante;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,length=20) private FrequenciaAssistencial frequenciaAnterior;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,length=20) private FrequenciaAssistencial frequenciaResultante;
    @Column(nullable=false,length=2000) private String justificativa;
    @Column(nullable=false) private Instant ocorridoEm;
    @Column(nullable=false) private Integer versaoAnterior;
    @Column(nullable=false) private Integer versaoResultante;
    public AuditoriaRegularizacaoCancelamento(Agendamento a,Usuario actor,String unit,String type,String reason,Instant when,int before){
        agendamento=a;paciente=a.getPaciente();responsavel=a.getUsuario();ator=actor;perfil=actor.getTipoUsuario();unidade=unit;
        tipoRegularizacao=type;reservaAnterior=SituacaoReserva.CANCELADA;reservaResultante=a.getSituacaoReserva();
        frequenciaAnterior=FrequenciaAssistencial.NAO_REGISTRADA;frequenciaResultante=a.getFrequenciaAssistencial();
        justificativa=reason;ocorridoEm=when;versaoAnterior=before;versaoResultante=a.getVersion();
    }
}
