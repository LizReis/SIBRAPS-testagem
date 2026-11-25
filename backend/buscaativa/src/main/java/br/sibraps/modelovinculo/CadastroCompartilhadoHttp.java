package br.sibraps.modelovinculo;

import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.validator.constraints.br.CPF;
import com.pet.buscaativa.entities.Endereco;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.dto.PacienteDTO;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.RacaCorEnum;
import com.pet.buscaativa.entities.enums.SexoEnum;
import com.pet.buscaativa.validation.CNS;
import jakarta.validation.constraints.*;

record CadastroCompartilhadoHttp(UUID idPublico, @NotBlank String nome, @NotBlank String nomeMae,
        @NotNull @PastOrPresent LocalDate dataNascimento, @NotNull SexoEnum sexo, @NotNull RacaCorEnum racacor,
        @NotBlank @CNS String cns, @NotBlank @CPF String cpf, @NotBlank String telefone, Endereco endereco,
        boolean situacaoRua, @NotNull UsfReferencia usfReferencia, @NotNull CapsEnum capsReferencia,
        @Null Integer countFaltas) {

    @AssertTrue(message = "Informe o endereço do paciente.")
    public boolean isEnderecoInformado() {
        return situacaoRua || endereco != null;
    }

    PacienteDTO dados() {
        return new PacienteDTO(idPublico, nome, nomeMae, dataNascimento, null, sexo, racacor, cns, cpf,
                telefone, endereco, situacaoRua, null, null, null, usfReferencia, capsReferencia, null, null);
    }
}
