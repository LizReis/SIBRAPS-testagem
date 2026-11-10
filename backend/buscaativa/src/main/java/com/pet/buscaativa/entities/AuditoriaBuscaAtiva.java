package com.pet.buscaativa.entities;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.pet.buscaativa.entities.enums.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="tb_auditoria_busca_ativa",uniqueConstraints=@UniqueConstraint(name="uk_evento_ator_chave",columnNames={"ator_id","chave_requisicao"}),indexes={
    @Index(name="ix_evento_paciente",columnList="paciente_id,unidade,ocorrido_em,id"),@Index(name="ix_evento_unidade",columnList="unidade,ocorrido_em,id")})
@Immutable @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
@Check(name="ck_evento_busca_integridade",constraints="quantidade >= 0 and char_length(trim(unidade)) > 0 and perfil in ('ADMINISTRADOR','PROFISSIONAL','RECEPCAO') and (not gatilho_confirmado or not indicadores_pendentes) and ((tipo_evento='CONSULTA_LISTA_BUSCA_ATIVA' and paciente_id is null and destino is null and descricao is null) or (tipo_evento='VISUALIZACAO_ALERTA' and paciente_id is not null and destino is null and descricao is null and quantidade=1) or (tipo_evento='ENCAMINHAMENTO_ALERTA' and paciente_id is not null and destino is not null and descricao is not null and char_length(trim(destino)) between 1 and 200 and char_length(trim(descricao)) between 10 and 1000 and quantidade=1 and perfil in ('ADMINISTRADOR','PROFISSIONAL') and not indicadores_pendentes))")
public class AuditoriaBuscaAtiva {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="paciente_id") private Paciente paciente;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="ator_id",nullable=false) private Usuario ator;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable=false,length=30) private TipoUsuario perfil;
    @Column(nullable=false,length=80) private String unidade;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name="tipo_evento",nullable=false,length=40) private TipoEventoBuscaAtiva tipoEvento;
    @Column(name="chave_requisicao",nullable=false) private UUID chaveRequisicao;
    @Column(name="ocorrido_em",nullable=false) private Instant ocorridoEm;
    @Column(nullable=false,length=80) private String situacao;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(length=20) private ClassificacaoRisco risco;
    @Column(name="gatilho_confirmado",nullable=false) private boolean gatilhoConfirmado;
    @Column(name="indicadores_pendentes",nullable=false) private boolean indicadoresPendentes;
    @Column(name="situacao_rua",nullable=false) private boolean situacaoRua;
    @Column(nullable=false) private int quantidade;
    @Column(length=200) private String destino;
    @Column(length=1000) private String descricao;

    public AuditoriaBuscaAtiva(Paciente patient,Usuario actor,String unit,TipoEventoBuscaAtiva type,UUID requestKey,Instant when,
            String situation,int count,String destination,String description) {
        paciente=patient;ator=actor;perfil=actor.getTipoUsuario();unidade=unit;tipoEvento=type;chaveRequisicao=requestKey;ocorridoEm=when;
        situacao=situation;quantidade=count;destino=destination;descricao=description;
        if(patient!=null){risco=patient.getClassificacaoRisco();indicadoresPendentes=Boolean.TRUE.equals(patient.getIndicadoresPendentes());
            gatilhoConfirmado=!indicadoresPendentes&&Boolean.TRUE.equals(patient.getGatilhoVisitaAcionado());situacaoRua=patient.isSituacaoRua();}
    }
}
