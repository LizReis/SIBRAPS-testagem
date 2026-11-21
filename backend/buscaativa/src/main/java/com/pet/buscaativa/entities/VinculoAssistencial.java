package com.pet.buscaativa.entities;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.envers.Audited;

import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Identidade global do Paciente + escopo assistencial local explicitamente registrado.
 *
 * Primeira entrega ADITIVA do cadastro unico: nao altera nem substitui, por ora,
 * as consultas e as regras clinicas legadas. Nunca derivar um vinculo a partir
 * de capsReferencia / usfReferencia (que sao apenas referencias cadastrais).
 */
@Audited
@Entity
@Table(name = "tb_vinculo_assistencial", uniqueConstraints = {
        @UniqueConstraint(name = "uk_vinculo_paciente_unidade", columnNames = {"paciente_id", "unidade_chave"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VinculoAssistencial extends AbstractEntities {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_publico", nullable = false, updatable = false, unique = true)
    private UUID idPublico = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paciente_id", nullable = false, updatable = false)
    private Paciente paciente;

    @Convert(converter = UnidadeAtuacaoConverter.class)
    @Column(name = "unidade_atuacao", nullable = false, updatable = false)
    private UnidadeAtuacao unidadeAtuacao;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usf_atuacao_id", updatable = false)
    private UsfReferencia usfAtuacao;

    /** Chave de escopo estavel. USF:<id> ou CAPS_AD/CAPS_I/CAPS_II. */
    @Column(name = "unidade_chave", length = 80, nullable = false, updatable = false)
    private String unidadeChave;

    @Convert(converter = StatusPacienteConverter.class)
    @Column(name = "status_paciente")
    private StatusPaciente statusPaciente;

    @Convert(converter = TipoAcompanhamentoConverter.class)
    @Column(name = "tipo_acompanhamento")
    private TipoAcompanhamento tipoAcompanhamento;

    @Column(name = "count_faltas")
    private Integer countFaltas;

    @Column(name = "data_ultima_presenca")
    private LocalDate dataUltimaPresenca;

    @Convert(converter = ClassificacaoRiscoConverter.class)
    @Column(name = "classificacao_risco")
    private ClassificacaoRisco classificacaoRisco;

    @Column(name = "gatilho_visita")
    private Boolean gatilhoVisitaAcionado;

    @Column(name = "indicadores_pendentes")
    private Boolean indicadoresPendentes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profissional_referencia_id")
    private Usuario profissionalReferencia;

    @Version
    private Integer version;

    public static VinculoAssistencial paraUsf(Paciente pessoa, UsfReferencia usf) {
        Objects.requireNonNull(pessoa, "Paciente obrigatorio");
        Objects.requireNonNull(usf, "USF obrigatoria");
        if (usf.getId() == null) throw new IllegalArgumentException("USF precisa estar persistida.");
        VinculoAssistencial vinculo = new VinculoAssistencial();
        vinculo.paciente = pessoa;
        vinculo.unidadeAtuacao = UnidadeAtuacao.USF;
        vinculo.usfAtuacao = usf;
        vinculo.unidadeChave = ChaveUnidadeAssistencial.daUsf(usf);
        vinculo.statusPaciente = StatusPaciente.ATIVO;
        return vinculo;
    }

    public static VinculoAssistencial paraCaps(Paciente pessoa, UnidadeAtuacao caps) {
        Objects.requireNonNull(pessoa, "Paciente obrigatorio");
        if (caps == null || caps == UnidadeAtuacao.USF)
            throw new IllegalArgumentException("Vinculo CAPS exige CAPS_AD, CAPS_I ou CAPS_II.");
        VinculoAssistencial vinculo = new VinculoAssistencial();
        vinculo.paciente = pessoa;
        vinculo.unidadeAtuacao = caps;
        vinculo.unidadeChave = ChaveUnidadeAssistencial.doCaps(caps);
        vinculo.statusPaciente = StatusPaciente.ATIVO;
        return vinculo;
    }

    @PrePersist
    @PreUpdate
    private void validarIdentidadeDoVinculo() {
        if (paciente == null || unidadeAtuacao == null || unidadeChave == null)
            throw new IllegalStateException("Vinculo assistencial sem identidade/unidade.");
        String esperado = ChaveUnidadeAssistencial.doVinculo(unidadeAtuacao, usfAtuacao);
        if (!esperado.equals(unidadeChave))
            throw new IllegalStateException("Unidade do vinculo incoerente.");
    }
}
