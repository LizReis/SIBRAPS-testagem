package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.validator.constraints.br.CPF;

import com.pet.buscaativa.entities.Endereco;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.RacaCorEnum;
import com.pet.buscaativa.entities.enums.SexoEnum;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.validation.CNS;
import com.pet.buscaativa.validation.EnderecoObrigatorio;

import jakarta.validation.constraints.*;

@EnderecoObrigatorio
public record PacienteDTO(
        UUID idPublico,

        @NotBlank
        @NotNull(message = "Informe o nome do Paciente.")
        String nome,

        @NotBlank
        @NotNull(message = "Informe o nome da Mãe do Paciente.")
        String nomeMae,

        @NotNull(message = "Informe a data de Nascimento do Paciente.")
        @PastOrPresent(message = "A data de nascimento não pode estar no futuro.")
        LocalDate dataNascimento,

        LocalDate dataUltimaPresenca,

        @NotNull(message = "Informe o Sexo do Paciente.")
        SexoEnum sexo,

        @NotNull(message = "Informe a Raça/Cor do Paciente.")
        RacaCorEnum racacor,

        @NotBlank
        @NotNull(message = "Informe o CNS do paciente.")
        @CNS
        String cns,


        @NotBlank
        @NotNull(message = "Informe o CPF do Paciente.")
        @CPF(message = "CPF com formato inválido.")
        String cpf,

        @NotBlank
        @NotNull(message = "Informe o número de Telefone do Paciente")
        String telefone,

        Endereco endereco,

        boolean situacaoRua,

        @NotNull(message = "Informe o Tipo de Acompanhamento do Paciente.")
        TipoAcompanhamento tipoAcompanhamento,

        Integer countFaltas,

        StatusPaciente statusPaciente,

        @NotNull(message = "Informe a USF de Referência do Paciente.")
        UsfReferencia usfReferencia,

        @NotNull(message = "Informe o CAPS de Referência do Paciente.")
        CapsEnum capsReferencia,

        UUID profissionalReferenciaId,

        UsuarioReferenciaDTO profissionalReferencia,
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        boolean indicadoresPendentes

) {
    public PacienteDTO(Paciente entity) {
        this(
                entity.getIdPublico(),
                entity.getNome(),
                entity.getNomeMae(),
                entity.getDataNascimento(),
                entity.getDataUltimaPresenca(),
                entity.getSexo(),
                entity.getRacacor(),
                entity.getCns(),
                entity.getCpf(),
                entity.getTelefone(),
                entity.getEndereco(),
                entity.isSituacaoRua(),
                entity.getTipoAcompanhamento(),
                entity.getCountFaltas(),
                entity.getStatusPaciente(),
                entity.getUsfReferencia(),
                entity.getCapsReferencia(),
                entity.getProfissionalReferencia() == null ? null : entity.getProfissionalReferencia().getIdPublico(),
                entity.getProfissionalReferencia() == null ? null : new UsuarioReferenciaDTO(entity.getProfissionalReferencia()),
                Boolean.TRUE.equals(entity.getIndicadoresPendentes())
        );
    }

    public PacienteDTO(UUID idPublico, String nome, String nomeMae, LocalDate dataNascimento, LocalDate dataUltimaPresenca,
            SexoEnum sexo, RacaCorEnum racacor, String cns, String cpf, String telefone, Endereco endereco,
            boolean situacaoRua, TipoAcompanhamento tipoAcompanhamento, Integer countFaltas, StatusPaciente statusPaciente,
            UsfReferencia usfReferencia, CapsEnum capsReferencia, UUID profissionalReferenciaId, UsuarioReferenciaDTO profissionalReferencia) {
        this(idPublico, nome, nomeMae, dataNascimento, dataUltimaPresenca, sexo, racacor, cns, cpf, telefone, endereco,
                situacaoRua, tipoAcompanhamento, countFaltas, statusPaciente, usfReferencia, capsReferencia,
                profissionalReferenciaId, profissionalReferencia, false);
    }

}
