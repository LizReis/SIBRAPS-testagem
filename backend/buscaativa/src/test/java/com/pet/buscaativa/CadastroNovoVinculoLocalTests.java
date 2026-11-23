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
import com.pet.buscaativa.entities.dto.PacienteDTO;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.RacaCorEnum;
import com.pet.buscaativa.entities.enums.SexoEnum;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.PacienteService;
import com.pet.buscaativa.services.exceptions.RecursoDuplicadoException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.SusApiAdapter;

/**
 * Nova identidade criada por profissional/recepcao/admin cria somente vinculo
 * de sua unidade autenticada. Nao reinterpreta referencias territoriais para
 * vincular CAPS/USFs, nao altera indicadores ou historicos clinicos legados.
 *
 * H2 sintetico e rollback por metodo: sem gravacao de pacientes reais.
 */
@SpringBootTest
@Transactional
class CadastroNovoVinculoLocalTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;
    @Autowired PacienteService service;
    @Autowired PacienteRepository pacientes;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired UsuarioRepository usuarios;
    @Autowired UsfReferenciaRepository usfs;

    @AfterEach void limpar() {
        SecurityContextHolder.clearContext();
    }

    private Usuario ator(UnidadeAtuacao unidade, UsfReferencia usf, TipoUsuario perfil) {
        Usuario usuario = new Usuario();
        usuario.setNome("Cadastro sintetico " + UUID.randomUUID());
        usuario.setEmail(UUID.randomUUID() + "@test.local");
        usuario.setSenha("somente-para-testes");
        usuario.setTipoUsuario(perfil);
        usuario.setUnidadeAtuacao(unidade);
        usuario.setUsfAtuacao(usf);
        return usuarios.saveAndFlush(usuario);
    }

    private UsfReferencia usf(String nome) {
        UsfReferencia unidade = new UsfReferencia();
        unidade.setCnes(UUID.randomUUID().toString());
        unidade.setNomeUsf(nome);
        return usfs.saveAndFlush(unidade);
    }

    private void autenticar(Usuario usuario) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        usuario, null, usuario.getAuthorities()));
    }

    private PacienteDTO dados(String cpf, String cns,
            UsfReferencia referenciaUsf, CapsEnum referenciaCaps) {
        return new PacienteDTO(null, "Pessoa sintetica nova", "Mae sintetica",
                LocalDate.of(1995, 1, 1), null,
                SexoEnum.FEMININO, RacaCorEnum.PARDA, cns, cpf, "75999999999",
                null, true, TipoAcompanhamento.INDIVIDUAL,
                null, null, referenciaUsf, referenciaCaps, null, null);
    }

    @Test
    void profissionalCapsCadastraPessoaNovaEVinculaApenasAoSeuCaps() {
        UsfReferencia territorial = usf("USF de referencia territorial");
        Usuario profissional = ator(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.PROFISSIONAL);
        autenticar(profissional);

        PacienteDTO resultado = service.save(
                dados("12312312312", "100000000000123", territorial, CapsEnum.CAPS_AD), false);
        Paciente p = pacientes.findByIdPublico(resultado.idPublico()).orElseThrow();
        assertEquals(1, pacientes.count());
        assertEquals(1, vinculos.findByPacienteId(p.getId()).size());
        assertTrue(vinculos.findByPacienteIdAndUnidadeChave(p.getId(), "CAPS_AD").isPresent());
        assertTrue(vinculos.findByPacienteIdAndUnidadeChave(p.getId(),
                "USF:" + territorial.getId()).isEmpty());
        assertEquals(0, p.getCountFaltas());
        assertEquals(CapsEnum.CAPS_AD, p.getCapsReferencia());
    }

    @Test
    void recepcaoUsfCadastraNaPropriaUsfSemVincularCapsDeReferencia() {
        UsfReferencia a = usf("USF propria");
        Usuario recepcao = ator(UnidadeAtuacao.USF, a, TipoUsuario.RECEPCAO);
        autenticar(recepcao);

        PacienteDTO novo = service.save(
                dados("23423423423", "100000000000234", a, CapsEnum.CAPS_II), false);
        Paciente p = pacientes.findByIdPublico(novo.idPublico()).orElseThrow();
        assertEquals(1, vinculos.findByPacienteId(p.getId()).size());
        assertTrue(vinculos.findByPacienteIdAndUnidadeChave(p.getId(),
                "USF:" + a.getId()).isPresent());
        assertTrue(vinculos.findByPacienteIdAndUnidadeChave(p.getId(), "CAPS_II").isEmpty());
    }

    @Test
    void administradorCapsNovoCadastroEEdicaoNaoCriamSegundoVinculo() {
        UsfReferencia referencia = usf("USF territorial");
        Usuario administrador = ator(UnidadeAtuacao.CAPS_I, null, TipoUsuario.ADMINISTRADOR);
        autenticar(administrador);
        PacienteDTO novo = service.save(
                dados("34534534534", "100000000000345", referencia, CapsEnum.CAPS_I), false);
        Paciente p = pacientes.findByIdPublico(novo.idPublico()).orElseThrow();
        assertEquals(1, vinculos.findByPacienteId(p.getId()).size());

        PacienteDTO edicao = new PacienteDTO(novo.idPublico(), novo.nome(), novo.nomeMae(),
                novo.dataNascimento(), novo.dataUltimaPresenca(), novo.sexo(),
                novo.racacor(), novo.cns(), novo.cpf(), novo.telefone(), novo.endereco(),
                novo.situacaoRua(), novo.tipoAcompanhamento(), null, novo.statusPaciente(),
                novo.usfReferencia(), novo.capsReferencia(), null, null);
        service.save(edicao, false);
        assertEquals(1, pacientes.count());
        assertEquals(1, vinculos.findByPacienteId(p.getId()).size());
    }

    @Test
    void cpfDuplicadoNaoCriaNovaIdentidadeNemVinculoNoCapsDeOutraUnidade() {
        UsfReferencia referencia = usf("USF de referencia");
        autenticar(ator(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.PROFISSIONAL));
        var primeiro = service.save(
                dados("45645645645", "100000000000456", referencia, CapsEnum.CAPS_AD), false);
        Paciente p = pacientes.findByIdPublico(primeiro.idPublico()).orElseThrow();
        autenticar(ator(UnidadeAtuacao.CAPS_II, null, TipoUsuario.ADMINISTRADOR));
        assertThrows(RuntimeException.class, () -> service.save(
                dados("45645645645", "100000000000456", referencia, CapsEnum.CAPS_II), false));
        assertEquals(1, pacientes.count());
        assertEquals(1, vinculos.findByPacienteId(p.getId()).size());
        assertTrue(vinculos.findByPacienteIdAndUnidadeChave(p.getId(), "CAPS_II").isEmpty());
    }
}
