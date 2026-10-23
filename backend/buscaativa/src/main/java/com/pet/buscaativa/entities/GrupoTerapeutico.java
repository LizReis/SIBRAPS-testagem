package com.pet.buscaativa.entities;

import java.io.Serializable;
import java.time.LocalTime;
import java.time.LocalDate;

import org.hibernate.envers.Audited;

import com.pet.buscaativa.entities.enums.RecorrenciaGrupo;

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
import jakarta.persistence.Version;
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
@Table(name = "tb_grupo_terapeutico")
public class GrupoTerapeutico extends AbstractEntities implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String tema;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coordenador_id", nullable = false)
    private Usuario coordenador;

    @Enumerated(EnumType.STRING)
    private RecorrenciaGrupo recorrencia;

    private LocalTime horarioPadrao;

    private LocalDate dataFimRecorrencia;

    private boolean ativo = true;

    @Version
    private Integer version;
}