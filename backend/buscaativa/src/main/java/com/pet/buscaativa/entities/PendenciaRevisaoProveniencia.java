package com.pet.buscaativa.entities;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Situacao;

/**
 * Contrato JPA ADITIVO da futura pendencia de revisao, ainda SEM fluxo de
 * criacao ou endpoint. Uma pendencia e somente solicitacao: jamais concede
 * acesso ao historico ou atribui proveniencia retroativamente.
 *
 * A unidade e o paciente devem coincidir com o vinculo referenciado; V12
 * aplica a integridade no banco e impede UPDATE/DELETE da solicitacao.
 * A decisao de um revisor sera um registro de auditoria separado.
 */
@Audited
@Immutable
@Entity
@Table(name = "tb_pendencia_revisao_proveniencia")
@Check(name = "ck_pendencia_revisao_integridade", constraints =
        "length(trim(unidade_chave)) > 0 and tipo_registro in ('AGENDAMENTO','HISTORICO','SESSAO_GRUPO') "
        + "and registro_id > 0 and situacao_evidencia in ("
        + "'REVISAR_ORIGEM_DESCONHECIDA','REVISAR_FONTE_SEM_ORIGEM',"
        + "'REVISAR_ORIGENS_CONFLITANTES','REVISAR_INTEGRIDADE_DA_FONTE',"
        + "'COMPATIVEL_SOMENTE_PARA_CONFERENCIA') "
        + "and length(trim(justificativa)) between 10 and 2000 "
        + "and perfil_solicitante in ('ADMINISTRADOR','PROFISSIONAL') and estado='PENDENTE'")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PendenciaRevisaoProveniencia {

    public enum TipoRegistro { AGENDAMENTO, HISTORICO, SESSAO_GRUPO }
    public enum Estado { PENDENTE }

    @Id
    @Column(nullable = false)
    private UUID id;

    // Mapeamento Java usa a PK do vínculo; a FK COMPOSTA no banco (V12)
    // garante também paciente_id e unidade_chave, inclusive para SQL direto.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vinculo_id", nullable = false, updatable = false)
    private VinculoAssistencial vinculo;

    @Column(name = "paciente_id", nullable = false, updatable = false)
    private Long pacienteId;

    @Column(name = "unidade_chave", length = 80, nullable = false, updatable = false)
    private String unidadeChave;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tipo_registro", length = 20, nullable = false, updatable = false)
    private TipoRegistro tipoRegistro;

    @Column(name = "registro_id", nullable = false, updatable = false)
    private Long registroId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "situacao_evidencia", length = 50, nullable = false, updatable = false)
    private Situacao situacaoEvidencia;

    @Column(name = "justificativa", length = 2000, nullable = false, updatable = false)
    private String justificativa;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "solicitante_id", nullable = false, updatable = false)
    private Usuario solicitante;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "perfil_solicitante", length = 30, nullable = false, updatable = false)
    private TipoUsuario perfilSolicitante;

    @Column(name = "solicitado_em", nullable = false, updatable = false)
    private Instant solicitadoEm;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "estado", length = 20, nullable = false, updatable = false)
    private Estado estado = Estado.PENDENTE;

    /**
     * Construido somente pelo servico interno de solicitacao, apos verificar
     * o fato real e o vinculo do usuario autenticado. Nao e prova retroativa
     * de proveniencia. Evidencia inconclusiva NAO pode usar este caminho.
     */
    public static PendenciaRevisaoProveniencia paraFatoLocalComprovado(
            VinculoAssistencial vinculo,
            TipoRegistro tipoRegistro,
            Long registroId,
            String justificativaNormalizada,
            Usuario solicitante,
            Instant solicitadoEm) {
        Objects.requireNonNull(vinculo, "Vinculo obrigatorio.");
        Objects.requireNonNull(tipoRegistro, "Tipo obrigatorio.");
        Objects.requireNonNull(solicitante, "Solicitante obrigatorio.");
        Objects.requireNonNull(solicitadoEm, "Data obrigatoria.");
        if (vinculo.getId() == null || vinculo.getPaciente() == null
                || vinculo.getPaciente().getId() == null
                || vinculo.getUnidadeChave() == null
                || vinculo.getUnidadeChave().isBlank()
                || registroId == null || registroId <= 0)
            throw new IllegalArgumentException("Vinculo ou registro sem identidade persistida.");
        if (solicitante.getId() == null || (solicitante.getTipoUsuario() != TipoUsuario.ADMINISTRADOR
                && solicitante.getTipoUsuario() != TipoUsuario.PROFISSIONAL))
            throw new IllegalArgumentException("Solicitante sem perfil assistencial permitido.");
        if (justificativaNormalizada == null || justificativaNormalizada.length() < 10
                || justificativaNormalizada.length() > 2000
                || justificativaNormalizada.isBlank())
            throw new IllegalArgumentException("Justificativa invalida.");

        PendenciaRevisaoProveniencia p = new PendenciaRevisaoProveniencia();
        p.id = UUID.randomUUID();
        p.vinculo = vinculo;
        p.pacienteId = vinculo.getPaciente().getId();
        p.unidadeChave = vinculo.getUnidadeChave();
        p.tipoRegistro = tipoRegistro;
        p.registroId = registroId;
        // Apenas fatos com origem LOCAL COMPROVADA por servico independente.
        // NAO aceitar Situacao a partir de parametro de cliente.
        p.situacaoEvidencia = Situacao.COMPATIVEL_SOMENTE_PARA_CONFERENCIA;
        p.justificativa = justificativaNormalizada;
        p.solicitante = solicitante;
        p.perfilSolicitante = solicitante.getTipoUsuario();
        p.solicitadoEm = solicitadoEm;
        p.estado = Estado.PENDENTE;
        return p;
    }

    /** Exclusivo para H1: fonte local verificada, origem do histórico ainda ausente. */
    public static PendenciaRevisaoProveniencia paraHistoricoSemOrigemComFonteLocal(
            VinculoAssistencial vinculo,Long registroId,String justificativa,Usuario solicitante,Instant instante) {
        if(solicitante==null || solicitante.getTipoUsuario()!=TipoUsuario.ADMINISTRADOR)
            throw new IllegalArgumentException("Revisão de origem exige administrador local.");
        var p=paraFatoLocalComprovado(vinculo,TipoRegistro.HISTORICO,registroId,justificativa,solicitante,instante);
        p.situacaoEvidencia=Situacao.REVISAR_ORIGEM_DESCONHECIDA;
        return p;
    }

    // Sem construtor publico ou setters: criar uma pendencia clinica futura
    // exigira operacao autorizada, evidencia conferida e justificativa.
}
