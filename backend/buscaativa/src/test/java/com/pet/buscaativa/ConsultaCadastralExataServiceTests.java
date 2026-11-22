package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.ConsultaCadastralExataService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

/**
 * Consulta de identidade sob autenticacao Spring Security real, H2 sintetico.
 * A resposta contem somente dados demograficos minimos, nao o identificador
 * global nem o estado assistencial de outra unidade.
 */
@SpringBootTest
@Transactional
class ConsultaCadastralExataServiceTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;
    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired ConsultaCadastralExataService consulta;

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
    }

    private Paciente pessoa(String cpf, String cns) {
        Paciente p = new Paciente();
        p.setNome("Paciente sintetica cadastrada");
        p.setNomeMae("Responsavel sintetica");
        p.setDataNascimento(LocalDate.of(1998, 8, 9));
        p.setCpf(cpf);
        p.setCns(cns);
        p.setCapsReferencia(CapsEnum.CAPS_II);
        return pacientes.saveAndFlush(p);
    }

    private Usuario usuario(UnidadeAtuacao unidade, TipoUsuario perfil) {
        Usuario u = new Usuario();
        u.setNome("Operador de conferencia cadastral");
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("somente-para-testes");
        u.setUnidadeAtuacao(unidade);
        u.setTipoUsuario(perfil);
        return usuarios.saveAndFlush(u);
    }

    private void autenticar(Usuario usuario) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        usuario, null, usuario.getAuthorities()));
    }

    @Test
    void profissionalConfereCadastroDeOutroCapsSemReceberDadoClinicoOuVinculo() {
        Paciente p = pessoa("11122233344", "123456789012345");
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));
        autenticar(usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.PROFISSIONAL));

        var identificacao = consulta.localizar("111.222.333-44", "12345 67890 12345");
        assertEquals(p.getNome(), identificacao.nome());
        assertEquals(p.getNomeMae(), identificacao.nomeMae());
        assertEquals(p.getDataNascimento(), identificacao.dataNascimento());
        assertEquals(1, pacientes.count());
        assertEquals(1, vinculos.findByPacienteId(p.getId()).size());
        assertTrue(vinculos.findByPacienteIdAndUnidadeChave(p.getId(), "CAPS_AD").isEmpty());
        assertEquals(Set.of("nome", "nomeMae", "dataNascimento"),
                Arrays.stream(ConsultaCadastralExataService.IdentidadeCadastral.class
                        .getRecordComponents()).map(c -> c.getName())
                        .collect(Collectors.toSet()));
    }

    @Test
    void recepcaoConfereCnsExatoSemCriarNovoPaciente() {
        Paciente p = pessoa("44433322211", "987654321012345");
        autenticar(usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.RECEPCAO));
        assertEquals(p.getNome(), consulta.localizar(null, "987654321012345").nome());
        assertEquals(1, pacientes.count());
        assertTrue(vinculos.findByPacienteId(p.getId()).isEmpty());
    }

    @Test
    void documentosDePacientesDiferentesBloqueiamConsultaEReusoImplicito() {
        Paciente a = pessoa("55511122233", "123456789012346");
        Paciente b = pessoa("77711122233", "123456789012347");
        autenticar(usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.ADMINISTRADOR));
        assertThrows(ValidationException.class,
                () -> consulta.localizar(a.getCpf(), b.getCns()));
        assertThrows(ValidationException.class,
                () -> consulta.localizar(a.getCpf(), "987654321098765"));
        assertEquals(2, pacientes.count());
        assertTrue(vinculos.findByPacienteId(a.getId()).isEmpty());
        assertTrue(vinculos.findByPacienteId(b.getId()).isEmpty());
    }

    @Test
    void pesquisaEmBrancoInvalidaOuDesconhecidaNaoRetornaDados() {
        autenticar(usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.PROFISSIONAL));
        assertThrows(ValidationException.class, () -> consulta.localizar(null, null));
        assertThrows(ValidationException.class, () -> consulta.localizar("123", null));
        assertThrows(ValidationException.class, () -> consulta.localizar("cpf:11122233344", null));
        assertThrows(ResourceNotFoundException.class,
                () -> consulta.localizar("66600000006", null));
    }

    @Test
    void unidadeNaoConfiguradaENaoAutenticadoNaoLocalizamPessoa() {
        Paciente p = pessoa("99900000009", "900000000000009");
        var usuarioInvalido = usuario(UnidadeAtuacao.USF, TipoUsuario.PROFISSIONAL);
        autenticar(usuarioInvalido);
        assertThrows(ValidationException.class,
                () -> consulta.localizar(p.getCpf(), null));
        SecurityContextHolder.clearContext();
        assertThrows(AccessDeniedException.class,
                () -> consulta.localizar(p.getCpf(), null));
    }
}
