package com.pet.buscaativa.entities;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.envers.Audited;

import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.Column;
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
@Table(name = "tb_sessao_grupo")
@org.hibernate.annotations.Check(name="ck_sessao_responsabilidade", constraints="status <> 'REALIZADA' or ((responsavel_atendimento_id is not null and responsabilidade_pendente = false and motivo_responsabilidade_pendente is null) or (responsavel_atendimento_id is null and responsabilidade_pendente = true and motivo_responsabilidade_pendente is not null))")
public class SessaoGrupo extends AbstractEntities implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grupo_id", nullable = false)
    private GrupoTerapeutico grupo;

    private LocalDate dataSessao;

    private LocalTime horario;

    @Enumerated(EnumType.STRING)
    private StatusSessaoGrupo status;

    @Column(length = 1000)
    private String motivoCancelamento;

    @OneToMany(mappedBy = "sessaoGrupo", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SessaoGrupoParticipante> participantes = new ArrayList<>();

    /** Consolidated at effective confirmation, independent of later group coordination. */
    @Setter(lombok.AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responsavel_atendimento_id")
    private Usuario responsavelAtendimento;

    @Setter(lombok.AccessLevel.NONE)
    @Column(nullable = false)
    private boolean responsabilidadePendente;

    @Setter(lombok.AccessLevel.NONE)
    @Column(length = 200)
    private String motivoResponsabilidadePendente;

    @Setter(lombok.AccessLevel.NONE)
    @Column(length = 80)
    private String origemResponsabilidade;

    public void confirmarRealizacao(Usuario responsavelValidado) {
        if (status != StatusSessaoGrupo.AGENDADA || responsavelAtendimento != null)
            throw new IllegalStateException("A responsabilidade de sessão realizada não pode ser substituída.");
        responsavelAtendimento = java.util.Objects.requireNonNull(responsavelValidado);
        responsabilidadePendente = false;
        motivoResponsabilidadePendente = null;
        origemResponsabilidade = "CONFIRMACAO_REALIZACAO";
        status = StatusSessaoGrupo.REALIZADA;
    }

    @jakarta.persistence.PrePersist
    @jakarta.persistence.PreUpdate
    private void protegerResponsabilidadeDesconhecida() {
        // Unknown legacy facts remain facts; assigning the current coordinator would invent ownership.
        if (status == StatusSessaoGrupo.REALIZADA && responsavelAtendimento == null) {
            responsabilidadePendente = true;
            motivoResponsabilidadePendente = "RESPONSAVEL_HISTORICO_NAO_COMPROVADO";
            origemResponsabilidade = "PENDENTE_RECONCILIACAO";
        }
    }

    @Version
    private Integer version;
}