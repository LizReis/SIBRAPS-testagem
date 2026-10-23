package com.pet.buscaativa.entities;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.envers.Audited;

import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.MotivoEncerramento;
import com.pet.buscaativa.entities.enums.RacaCorEnum;
import com.pet.buscaativa.entities.enums.SexoEnum;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "tb_paciente")
public class Paciente extends AbstractEntities implements Serializable{

    private static final long serialVersionUID = 1L;
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_publico", unique = true, updatable = false, nullable = false)
    private UUID idPublico = UUID.randomUUID();

    private String nome;
    private String nomeMae;
    private LocalDate dataNascimento;
    private LocalDate dataUltimaPresenca;

    @Convert(converter = SexoEnumConverter.class)
    private SexoEnum sexo;

    @Convert(converter = RacaCorEnumConverter.class)
    private RacaCorEnum racacor;

    private String cns;

    private String cpf;

    private String telefone;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "endereco_id")
    private Endereco endereco;

    private boolean situacaoRua;

    @Convert(converter = TipoAcompanhamentoConverter.class)
    private TipoAcompanhamento tipoAcompanhamento;

    private int countFaltas;
    
    @Convert(converter = StatusPacienteConverter.class)
    private StatusPaciente statusPaciente;

    @ManyToOne
    @JoinColumn(name = "usf_cnes")
    private UsfReferencia usfReferencia;

    @Convert(converter = CapsEnumConverter.class)
    private CapsEnum capsReferencia;

    @ManyToOne
    @JoinColumn(name = "profissional_referencia_id", nullable = true)
    private Usuario profissionalReferencia;


    @Version
    private Integer version;

    @Convert(converter = ClassificacaoRiscoConverter.class)
    private ClassificacaoRisco classificacaoRisco = ClassificacaoRisco.VERDE;

    private LocalDate dataEncerramento;
    @Enumerated(EnumType.STRING)
    private MotivoEncerramento motivoEncerramento;
    private String descricaoMotivoEncerramento;
    private String profissionalEncerramento;

    private LocalDate dataReativacao;
    private String motivoReativacao;
    private String profissionalReativacao;

    @Column(name = "gatilho_visita")
    private Boolean gatilhoVisitaAcionado = false;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Paciente paciente = (Paciente) o;
        return Objects.equals(id, paciente.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
