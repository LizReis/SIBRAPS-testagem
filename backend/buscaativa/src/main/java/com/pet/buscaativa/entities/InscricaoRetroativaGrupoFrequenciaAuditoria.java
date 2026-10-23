package com.pet.buscaativa.entities;

import java.time.*; 
import jakarta.persistence.*; 
import lombok.*; 
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;

@Entity 
@Table(name="tb_inscricao_retroativa_grupo_frequencia_auditoria") 
@Getter 
@Setter 
@NoArgsConstructor
public class InscricaoRetroativaGrupoFrequenciaAuditoria {
    
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) 
    private Long id;

    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="auditoria_id") 
    private InscricaoRetroativaGrupoAuditoria auditoria;

    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="sessao_id") 
    private SessaoGrupo sessao;

    private LocalDate dataSessao;

    @Enumerated(EnumType.STRING) @Column(nullable=false) 
    private StatusPresencaGrupo statusPresenca;
}