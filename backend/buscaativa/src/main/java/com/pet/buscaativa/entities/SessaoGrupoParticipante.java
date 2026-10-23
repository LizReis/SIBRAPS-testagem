package com.pet.buscaativa.entities;

import java.io.Serializable;

import org.hibernate.envers.Audited;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Column;

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
@Table(name = "tb_sessao_grupo_participante", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sessao_paciente", columnNames = {"sessao_grupo_id", "paciente_id"})
})
public class SessaoGrupoParticipante implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sessao_grupo_id", nullable = false)
    private SessaoGrupo sessaoGrupo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paciente_id", nullable = false)
    private Paciente paciente;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusPresencaGrupo statusPresenca = StatusPresencaGrupo.NAO_REGISTRADA;
}