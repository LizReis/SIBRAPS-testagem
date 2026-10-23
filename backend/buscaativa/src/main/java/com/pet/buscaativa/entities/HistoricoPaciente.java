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

    /** Valor no instante do fato; não é recalculado com o contador atual. */
    private Integer numeroFaltaConsecutiva;
}