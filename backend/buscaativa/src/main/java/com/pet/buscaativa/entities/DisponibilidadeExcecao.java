package com.pet.buscaativa.entities;

import java.io.Serializable;
import java.time.LocalDate;

import org.hibernate.envers.Audited;

import com.pet.buscaativa.entities.enums.TurnoEnum;

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
import jakarta.persistence.UniqueConstraint;
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
@Table(name = "tb_disponibilidade_excecao", uniqueConstraints = {
        @UniqueConstraint(name = "uk_excecao_usuario_data_turno", columnNames = {"usuario_id", "data", "turno"})
})
public class DisponibilidadeExcecao extends AbstractEntities implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "data", nullable = false)
    private LocalDate data;

    @Convert(converter = TurnoEnumConverter.class)
    private TurnoEnum turno;

    // Capacidade pode ser 0 -> significa "fechar esse turno nessa data específica",
    // mesmo que o padrão semanal tenha vaga.
    @Column(nullable = false)
    private Integer capacidade;
}