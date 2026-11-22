package com.pet.buscaativa.entities;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.type.SqlTypes;

import com.pet.buscaativa.entities.enums.TipoUsuario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Mapeamento JPA da estrutura V13 de DECISAO HUMANA, ainda SEM criacao
 * operacional. Um registro de decisao nao altera a solicitacao V12, nao
 * resolve automaticamente a origem de um fato e nao concede acesso clinico.
 *
 * A FK composta (pendencia_id, paciente_id, unidade_chave) e as restricoes
 * de integridade sao aplicadas pela migracao offline V13 no H2 persistido.
 * A entidade permanece imutavel e auditavel; a futura gravacao requer
 * autorizacao humana, transacao e trilha de evidencias verificadas.
 */
@Audited
@Immutable
@Entity
@Table(name = "tb_decisao_revisao_proveniencia",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_decisao_revisao_ticket", columnNames = "pendencia_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DecisaoRevisaoProveniencia {
    public enum Decisao { APROVAR, REJEITAR }

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pendencia_id", nullable = false, updatable = false)
    private PendenciaRevisaoProveniencia pendencia;

    @Column(name = "paciente_id", nullable = false, updatable = false)
    private Long pacienteId;

    @Column(name = "unidade_chave", nullable = false, length = 80, updatable = false)
    private String unidadeChave;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20, updatable = false)
    private Decisao decisao;

    @Column(nullable = false, length = 2000, updatable = false)
    private String justificativa;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revisor_id", nullable = false, updatable = false)
    private Usuario revisor;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "perfil_revisor", nullable = false, length = 30, updatable = false)
    private TipoUsuario perfilRevisor;

    @Column(name = "decidida_em", nullable = false, updatable = false)
    private Instant decididaEm;

    /**
     * A criacao so pode ocorrer por comando interno apos escopo local,
     * verificacao do fato original e justificativa conferidos no servidor.
     * APROVAR aqui registra somente a decisao administrativa, jamais
     * reescreve a unidade historica, presenca ou outros dados clinicos.
     */
    public static DecisaoRevisaoProveniencia registrarRevisaoHumana(
            PendenciaRevisaoProveniencia pendencia,
            Decisao decisao,
            String justificativaNormalizada,
            Usuario revisor,
            Instant instante) {
        Objects.requireNonNull(pendencia, "Pendencia obrigatoria.");
        Objects.requireNonNull(decisao, "Decisao obrigatoria.");
        Objects.requireNonNull(revisor, "Revisor obrigatorio.");
        Objects.requireNonNull(instante, "Data de decisao obrigatoria.");
        if (pendencia.getId() == null || pendencia.getPacienteId() == null
                || pendencia.getUnidadeChave() == null
                || pendencia.getUnidadeChave().isBlank()
                || pendencia.getEstado() != PendenciaRevisaoProveniencia.Estado.PENDENTE)
            throw new IllegalArgumentException("Pendencia sem identidade e escopo validos.");
        if (revisor.getId() == null || revisor.getTipoUsuario() != TipoUsuario.ADMINISTRADOR)
            throw new IllegalArgumentException("Revisor administrativo persistido obrigatorio.");
        if (justificativaNormalizada == null || justificativaNormalizada.isBlank()
                || justificativaNormalizada.length() < 10
                || justificativaNormalizada.length() > 2000)
            throw new IllegalArgumentException("Justificativa invalida.");

        DecisaoRevisaoProveniencia evento = new DecisaoRevisaoProveniencia();
        evento.id = UUID.randomUUID();
        evento.pendencia = pendencia;
        evento.pacienteId = pendencia.getPacienteId();
        evento.unidadeChave = pendencia.getUnidadeChave();
        evento.decisao = decisao;
        evento.justificativa = justificativaNormalizada;
        evento.revisor = revisor;
        evento.perfilRevisor = revisor.getTipoUsuario();
        evento.decididaEm = instante;
        return evento;
    }
}
