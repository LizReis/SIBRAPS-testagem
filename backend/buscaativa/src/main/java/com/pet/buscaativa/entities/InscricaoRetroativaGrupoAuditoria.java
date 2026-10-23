package com.pet.buscaativa.entities;

import java.util.*; 
import jakarta.persistence.*; 
import lombok.*;


@Entity 
@Table(name="tb_inscricao_retroativa_grupo_auditoria") 
@Getter 
@Setter 
@NoArgsConstructor
public class InscricaoRetroativaGrupoAuditoria extends AbstractEntities {

    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) 
    private Long id;

    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="grupo_id") 
    private GrupoTerapeutico grupo;

    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="paciente_id") 
    private Paciente paciente;

    @OneToMany(mappedBy="auditoria",cascade=CascadeType.ALL,orphanRemoval=true) 
    private List<InscricaoRetroativaGrupoFrequenciaAuditoria> frequencias=new ArrayList<>();
}