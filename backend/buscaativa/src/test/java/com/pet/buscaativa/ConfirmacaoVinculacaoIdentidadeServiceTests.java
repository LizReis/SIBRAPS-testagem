package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.ConfirmacaoVinculacaoIdentidadeService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

/**
 * Confirma identidade por dados cadastrais antes da associacao explicita
 * ao proprio vinculo. Sem controller, migracao de historico ou acesso clinico.
 * Apenas registros artificiais no H2 de testes, com rollback por metodo.
 */
@SpringBootTest
@Transactional
class ConfirmacaoVinculacaoIdentidadeServiceTests extends SecurityTestSupport {
    private static final LocalDate NASCIMENTO = LocalDate.of(1996, 8, 11);
    private static final String NOME = "Maria Sintetica de Teste";
    private static final String MAE = "Responsavel Sintetica de Teste";

    @MockitoBean SusApiAdapter susApi;

    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired ConfirmacaoVinculacaoIdentidadeService confirmacao;

    @AfterEach void limpar() { SecurityContextHolder.clearContext(); }

    private Paciente pessoa(String cpf, String cns) {
        Paciente p = new Paciente();
        p.setNome(NOME);
        p.setNomeMae(MAE);
        p.setDataNascimento(NASCIMENTO);
        p.setCpf(cpf);
        p.setCns(cns);
        p.setCapsReferencia(CapsEnum.CAPS_II); // referencia nao concede vinculo
        return pacientes.saveAndFlush(p);
    }

    private Usuario operador(UnidadeAtuacao unidade, UsfReferencia usf, TipoUsuario perfil) {
        Usuario u = new Usuario();
        u.setNome("Operador sintetico de confirmacao");
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("somente-testes");
        u.setTipoUsuario(perfil);
        u.setUnidadeAtuacao(unidade);
        u.setUsfAtuacao(usf);
        return usuarios.saveAndFlush(u);
    }

    private UsfReferencia usf(String nome) {
        UsfReferencia u = new UsfReferencia();
        u.setCnes(UUID.randomUUID().toString());
        u.setNomeUsf(nome);
        return usfs.saveAndFlush(u);
    }

    private void autenticar(Usuario u) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        u, null, u.getAuthorities()));
    }

    private com.pet.buscaativa.services.impl.VinculacaoIdentidadeExistenteService.Resultado
            confirmar(String cpf, String cns, String nome, String mae, LocalDate nascimento) {
        return confirmacao.confirmarEVincular(cpf, cns, nome, mae, nascimento);
    }

    @Test
    void profissionalConfirmaPessoaDeOutroCapsECriaApenasSeuVinculo() {
        Paciente p = pessoa("12312312312", "100000000000123");
        autenticar(operador(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.PROFISSIONAL));
        var resultado = confirmar("123.123.123-12", "10000 00000 00123",
                "  MARIA  sintetica de teste ", MAE, NASCIMENTO);
        assertEquals(p.getIdPublico(), resultado.pacienteIdPublico());
        assertEquals("CAPS_AD", resultado.unidadeChave());
        assertEquals(1, pacientes.count());
        assertEquals(1, vinculos.findByPacienteId(p.getId()).size());
        assertTrue(vinculos.findByPacienteIdAndUnidadeChave(p.getId(), "CAPS_II").isEmpty());
        assertEquals(CapsEnum.CAPS_II, pacientes.findById(p.getId())
                .orElseThrow().getCapsReferencia());
        assertEquals(resultado.vinculoLocalIdPublico(),
                confirmar(p.getCpf(), null, NOME, MAE, NASCIMENTO)
                        .vinculoLocalIdPublico());
    }

    @Test
    void divergenciasDeNomeMaeOuNascimentoNaoCriamVinculo() {
        Paciente p = pessoa("23423423423", "100000000000234");
        autenticar(operador(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.ADMINISTRADOR));

        assertThrows(ValidationException.class, () ->
                confirmar(p.getCpf(), null, "Outra Pessoa", MAE, NASCIMENTO));
        assertThrows(ValidationException.class, () ->
                confirmar(p.getCpf(), null, NOME, "Outra Responsavel", NASCIMENTO));
        assertThrows(ValidationException.class, () ->
                confirmar(p.getCpf(), null, NOME, MAE, NASCIMENTO.plusDays(1)));
        assertEquals(0, vinculos.findByPacienteId(p.getId()).size());
    }

    @Test
    void recepcaoPodeConfirmarVinculoApenasDaSuaUsf() {
        Paciente p = pessoa("34534534534", "100000000000345");
        UsfReferencia primeiraUsf = usf("USF de teste");
        autenticar(operador(UnidadeAtuacao.USF, primeiraUsf, TipoUsuario.RECEPCAO));
        var resultado = confirmar(null, p.getCns(), NOME, MAE, NASCIMENTO);
        assertEquals("USF:" + primeiraUsf.getId(), resultado.unidadeChave());
        assertEquals(1, pacientes.count());
        assertEquals(1, vinculos.findByPacienteId(p.getId()).size());
    }

    @Test
    void documentosConflitantesNaoReaproveitamIdentidade() {
        Paciente primeira = pessoa("45645645645", "100000000000456");
        Paciente outra = pessoa("56756756756", "100000000000567");
        autenticar(operador(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.ADMINISTRADOR));
        assertThrows(ValidationException.class, () ->
                confirmar(primeira.getCpf(), outra.getCns(), NOME, MAE, NASCIMENTO));
        assertEquals(0, vinculos.findByPacienteId(primeira.getId()).size());
        assertEquals(0, vinculos.findByPacienteId(outra.getId()).size());
    }

    @Test
    void semConfirmacaoCompletaOuIdentidadeInexistenteNaoCriaVinculo() {
        Paciente p = pessoa("67867867867", "100000000000678");
        autenticar(operador(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.PROFISSIONAL));

        assertThrows(ValidationException.class, () ->
                confirmar(p.getCpf(), null, NOME, MAE, null));
        assertThrows(ValidationException.class, () ->
                confirmar(p.getCpf(), null, " ", MAE, NASCIMENTO));
        assertThrows(ResourceNotFoundException.class, () ->
                confirmar("99999999999", null, NOME, MAE, NASCIMENTO));
        assertEquals(0, vinculos.findByPacienteId(p.getId()).size());
    }
}
