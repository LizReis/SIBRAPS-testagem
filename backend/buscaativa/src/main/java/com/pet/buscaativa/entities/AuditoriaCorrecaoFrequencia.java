package com.pet.buscaativa.entities;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.pet.buscaativa.entities.enums.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An insert-only correction record; never a new attendance fact. */
@Entity @Table(name="tb_auditoria_correcao_frequencia",indexes={
    @Index(name="ix_correcao_agendamento",columnList="agendamento_id,ocorrido_em,id"),
    @Index(name="ix_correcao_sessao",columnList="sessao_id,ocorrido_em,id")})
@Immutable @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
@Check(name="ck_correcao_integridade",constraints="perfil in ('ADMINISTRADOR','PROFISSIONAL') and char_length(trim(unidade)) > 0 and frequencia_anterior in ('PRESENTE','FALTOU') and frequencia_nova in ('PRESENTE','FALTOU') and frequencia_anterior <> frequencia_nova and char_length(trim(justificativa)) between 10 and 2000 and versao_anterior >= 0 and versao_resultante > versao_anterior and ((tipo_correcao='INDIVIDUAL' and agendamento_id is not null and sessao_id is null and participante_id is null and situacao_reserva is not null and situacao_sessao is null) or (tipo_correcao='GRUPO' and agendamento_id is null and sessao_id is not null and participante_id is not null and situacao_reserva is null and situacao_sessao is not null and situacao_sessao='REALIZADA'))")
public class AuditoriaCorrecaoFrequencia {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="agendamento_id") private Agendamento agendamento;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="sessao_id") private SessaoGrupo sessao;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="participante_id") private SessaoGrupoParticipante participante;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="paciente_id",nullable=false) private Paciente paciente;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="ator_id",nullable=false) private Usuario ator;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="responsavel_id",nullable=false) private Usuario responsavel;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,length=30) private TipoUsuario perfil;
    @Column(nullable=false,length=80) private String unidade;
    @Column(name="tipo_correcao",nullable=false,length=20) private String tipoCorrecao;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name="situacao_reserva",length=20) private SituacaoReserva situacaoReserva;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name="situacao_sessao",length=20) private StatusSessaoGrupo situacaoSessao;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name="frequencia_anterior",nullable=false,length=20) private FrequenciaAssistencial frequenciaAnterior;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name="frequencia_nova",nullable=false,length=20) private FrequenciaAssistencial frequenciaNova;
    @Column(nullable=false,length=2000) private String justificativa;
    @Column(name="ocorrido_em",nullable=false) private Instant ocorridoEm;
    @Column(name="versao_anterior",nullable=false) private Integer versaoAnterior;
    @Column(name="versao_resultante",nullable=false) private Integer versaoResultante;

    public AuditoriaCorrecaoFrequencia(Agendamento appointment,SessaoGrupo session,SessaoGrupoParticipante participant,
            Paciente patient,Usuario actor,Usuario responsible,String unit,FrequenciaAssistencial before,
            FrequenciaAssistencial after,String reason,Instant when,int previousVersion,int resultingVersion) {
        agendamento=appointment;sessao=session;participante=participant;paciente=patient;ator=actor;responsavel=responsible;
        perfil=actor.getTipoUsuario();unidade=unit;tipoCorrecao=appointment!=null?"INDIVIDUAL":"GRUPO";
        situacaoReserva=appointment==null?null:appointment.getSituacaoReserva();situacaoSessao=session==null?null:session.getStatus();
        frequenciaAnterior=before;frequenciaNova=after;justificativa=reason;ocorridoEm=when;versaoAnterior=previousVersion;versaoResultante=resultingVersion;
    }
}
