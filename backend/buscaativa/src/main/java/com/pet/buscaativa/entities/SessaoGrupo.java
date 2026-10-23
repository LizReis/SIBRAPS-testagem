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

    @Version
    private Integer version;
}