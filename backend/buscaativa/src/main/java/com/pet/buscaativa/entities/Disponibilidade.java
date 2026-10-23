package com.pet.buscaativa.entities;

import java.io.Serializable;
import java.time.DayOfWeek;

import org.hibernate.envers.Audited;

import com.pet.buscaativa.entities.enums.TurnoEnum;

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
@Table(name = "tb_disponibilidade_prof", uniqueConstraints = {
        @UniqueConstraint(name = "uk_disponibilidade_usuario_dia_turno", columnNames = {"usuario_id", "dia_semana", "turno"})
    })
public class Disponibilidade extends AbstractEntities implements Serializable{
    
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Enumerated(EnumType.STRING) //é um enum do java, os dias estarão em ingles
    @Column(name = "dia_semana", nullable = false)
    private DayOfWeek diaDaSemana;

    @Convert(converter = TurnoEnumConverter.class)
    private TurnoEnum turno;

    @Column(nullable = false)
    private Integer capacidade;
}
