package com.pet.buscaativa.entities;

import java.time.LocalDateTime;

import org.hibernate.envers.Audited;

import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Registro imutável de um fato relevante ocorrido no acompanhamento do paciente. */
@Audited
@Entity
@Table(name = "tb_historico_paciente")
@Getter
@Setter
@NoArgsConstructor
public class HistoricoPaciente extends AbstractEntities {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paciente_id", nullable = false)
    private Paciente paciente;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agendamento_id")
    private Agendamento agendamento;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sessao_grupo_id")
    private SessaoGrupo sessaoGrupo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profissional_id")
    private Usuario profissional;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TipoEventoHistoricoPaciente tipo;

    @Convert(converter = SituacaoAtendimentoConverter.class)
    private SituacaoAtendimento situacaoAtendimento;

    @Column(nullable = false)
    private LocalDateTime ocorridoEm;

    @Column(length = 2000)
    private String descricao;

    /**
     * Proveniência LOCAL capturada somente na CRIAÇÃO de um evento.
     * Ausente em histórico antigo ou com fonte sem origem comprovada.
     * Um fato de atendimento/sessão recebe a origem desse fato, nunca a
     * lotação atual do profissional; alteração de frequência não regrava origem.
     */
    @Column(name = "unidade_origem_chave", length = 80, updatable = false)
    @Setter(AccessLevel.NONE)
    private String unidadeOrigemChave;

    public void registrarOrigemDoAgendamento(Agendamento origem) {
        if (origem == null || this.agendamento != origem
                || this.sessaoGrupo != null
                || paciente == null || origem.getPaciente() == null
                || paciente.getId() == null
                || !paciente.getId().equals(origem.getPaciente().getId()))
            throw new IllegalArgumentException("Histórico não corresponde ao agendamento de origem.");
        registrarOrigemNova(origem.getUnidadeOrigemChave());
    }

    public void registrarOrigemDaSessao(SessaoGrupo origem) {
        if (origem == null || this.sessaoGrupo != origem || this.agendamento != null
                || paciente == null)
            throw new IllegalArgumentException("Histórico não corresponde à sessão de origem.");
        registrarOrigemNova(origem.getUnidadeOrigemChave());
    }

    /** Exclusivo para fato novo SEM agendamento/sessão, com executor autenticado. */
    public void registrarOrigemDeAcaoLocal(Usuario executor) {
        if (agendamento != null || sessaoGrupo != null)
            throw new IllegalArgumentException("Histórico com fato clínico deve conservar a origem do próprio fato.");
        registrarOrigemNova(ChaveUnidadeAssistencial.doUsuarioAtual(
                java.util.Objects.requireNonNull(executor, "Executor obrigatório.")));
    }

    private void registrarOrigemNova(String chaveDaFonte) {
        if (id != null || unidadeOrigemChave != null)
            throw new IllegalStateException("Unidade histórica não pode ser substituída.");
        // NULL da fonte legada significa origem ainda não comprovada: manter NULL.
        if (chaveDaFonte != null) {
            if (chaveDaFonte.isBlank() || chaveDaFonte.length() > 80)
                throw new IllegalArgumentException("Chave de origem inválida.");
            unidadeOrigemChave = chaveDaFonte;
        }
    }

    /** Valor no instante do fato; não é recalculado com o contador atual. */
    private Integer numeroFaltaConsecutiva;
}