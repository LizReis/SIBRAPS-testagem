package com.pet.buscaativa.entities;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

import org.hibernate.annotations.Immutable;
import org.hibernate.envers.Audited;

import com.pet.buscaativa.entities.enums.MotivoEncerramento;
import com.pet.buscaativa.entities.enums.StatusPaciente;

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

/** Evento contemporâneo. Os valores clínicos iniciais estão na revisão ADD do vínculo. */
@Entity
@Audited
@Immutable
@Table(name = "tb_evento_estado_clinico_vinculo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventoEstadoClinicoVinculo {
    public enum Natureza { INICIALIZACAO, ENCERRAMENTO, REATIVACAO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vinculo_id", nullable = false)
    private VinculoAssistencial vinculo;

    @Column(name = "unidade_chave", nullable = false, length = 80)
    private String unidadeChave;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ator_id", nullable = false)
    private Usuario ator;

    @Column(name = "ocorrido_em", nullable = false)
    private LocalDateTime ocorridoEm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Natureza natureza;

    @Convert(converter = StatusPacienteConverter.class)
    @Column(name = "status_anterior")
    private StatusPaciente statusAnterior;

    @Convert(converter = StatusPacienteConverter.class)
    @Column(name = "status_resultante", nullable = false)
    private StatusPaciente statusResultante;

    @Column(length = 2000)
    private String motivo;

    @Column(length = 2000)
    private String descricao;

    public EventoEstadoClinicoVinculo(VinculoAssistencial vinculo, Usuario ator, LocalDateTime instante,
            Natureza natureza, StatusPaciente anterior, String motivo, String descricao) {
        this.vinculo = Objects.requireNonNull(vinculo);
        this.ator = Objects.requireNonNull(ator);
        this.unidadeChave = ChaveUnidadeAssistencial.doUsuarioAtual(ator);
        if (!unidadeChave.equals(vinculo.getUnidadeChave())) {
            throw new IllegalArgumentException("Ator fora da unidade do vínculo.");
        }
        this.ocorridoEm = Objects.requireNonNull(instante);
        this.natureza = Objects.requireNonNull(natureza);
        this.statusAnterior = anterior;
        this.statusResultante = Objects.requireNonNull(vinculo.getStatusPaciente());
        this.motivo = motivo;
        this.descricao = descricao;
        if (!coerente()) throw new IllegalArgumentException("Evento de estado local incoerente.");
    }

    public boolean coerente() {
        if (natureza == null || ocorridoEm == null || ator == null) return false;
        return switch (natureza) {
            case INICIALIZACAO -> statusAnterior == null && statusResultante == StatusPaciente.ATIVO
                    && motivo == null && descricao == null;
            case ENCERRAMENTO -> statusResultante != null && statusResultante != StatusPaciente.ATIVO
                    && statusAnterior != statusResultante
                    && Arrays.stream(MotivoEncerramento.values()).anyMatch(m -> m.name().equals(motivo))
                    && (!MotivoEncerramento.OUTRO.name().equals(motivo) || descricao != null && !descricao.isBlank());
            case REATIVACAO -> statusAnterior != null && statusAnterior != StatusPaciente.ATIVO
                    && statusResultante == StatusPaciente.ATIVO && motivo != null && !motivo.isBlank()
                    && descricao == null;
        };
    }
}
