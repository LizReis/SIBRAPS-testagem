package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.pet.buscaativa.services.impl.SusApiAdapter;

import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.UnidadeAtuacaoConverter;
import com.pet.buscaativa.entities.dto.UsuarioDTO;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.UsuarioService;
import com.pet.buscaativa.services.exceptions.ValidationException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Transactional
class UsuarioUnidadeTests {
    @MockitoBean SusApiAdapter susApi;
    @Autowired UsuarioService service;
    @Autowired UsuarioRepository usuarios;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired ObjectMapper json;

    private void autenticar(UsuarioDTO dto) {
        var usuario = usuarios.findByIdPublico(dto.idPublico()).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(usuario, null, usuario.getAuthorities()));
    }

    @AfterEach
    void limparAutenticacao() {
        SecurityContextHolder.clearContext();
    }

    private UsuarioDTO dto(UUID id, UnidadeAtuacao unidade, Long usfId) {
        return new UsuarioDTO(id, "Usuário teste", "unidade-" + (id == null ? UUID.randomUUID() : id) + "@test.local",
                TipoUsuario.PROFISSIONAL, unidade, usfId, null, id == null ? "senha-teste" : null);
    }

    private Long usf() {
        UsfReferencia usf = new UsfReferencia();
        usf.setCnes(UUID.randomUUID().toString());
        usf.setNomeUsf("USF teste");
        return usfs.save(usf).getId();
    }

    @ParameterizedTest
    @EnumSource(value = UnidadeAtuacao.class, names = {"CAPS_AD", "CAPS_I", "CAPS_II"})
    void cadastraCapsENormalizaUsfParaNull(UnidadeAtuacao unidade) {
        UsuarioDTO salvo = service.save(dto(null, unidade, usf()));
        var entity = usuarios.findByIdPublico(salvo.idPublico()).orElseThrow();
        assertEquals(unidade, entity.getUnidadeAtuacao());
        assertNull(entity.getUsfAtuacao());
        assertNull(salvo.usfAtuacaoId());
        assertNull(salvo.usfAtuacao());
    }

    @Test
    void cadastraUsfEspecificaComRetornoLeve() {
        Long id = usf();
        UsuarioDTO salvo = service.save(dto(null, UnidadeAtuacao.USF, id));
        assertEquals(id, usuarios.findByIdPublico(salvo.idPublico()).orElseThrow().getUsfAtuacao().getId());
        assertEquals(id, salvo.usfAtuacaoId());
        assertEquals("USF teste", salvo.usfAtuacao().nomeUsf());
        autenticar(salvo);
        assertEquals(id, service.findById(salvo.idPublico()).usfAtuacao().id());
        assertEquals(id, service.findByEmail(salvo.email()).usfAtuacaoId());
    }

    @Test
    void rejeitaUsfSemId() {
        assertThrows(ValidationException.class, () -> service.save(dto(null, UnidadeAtuacao.USF, null)));
    }

    @Test
    void rejeitaUsfInexistente() {
        assertThrows(ValidationException.class, () -> service.save(dto(null, UnidadeAtuacao.USF, Long.MAX_VALUE)));
    }

    @Test
    void rejeitaEdicaoDeUsfAParaUsfB() {
        UsuarioDTO a = service.save(dto(null, UnidadeAtuacao.USF, usf()));
        autenticar(a);
        Long b = usf();
        assertThrows(ValidationException.class, () -> service.save(dto(a.idPublico(), UnidadeAtuacao.USF, b)));
        assertEquals(a.usfAtuacaoId(), usuarios.findByIdPublico(a.idPublico()).orElseThrow().getUsfAtuacao().getId());
    }

    @Test
    void rejeitaEdicaoDeUsfParaCaps() {
        UsuarioDTO a = service.save(dto(null, UnidadeAtuacao.USF, usf()));
        autenticar(a);
        assertThrows(ValidationException.class, () -> service.save(dto(a.idPublico(), UnidadeAtuacao.CAPS_AD, a.usfAtuacaoId())));
        assertEquals(a.usfAtuacaoId(), usuarios.findByIdPublico(a.idPublico()).orElseThrow().getUsfAtuacao().getId());
    }

    @Test
    void rejeitaEdicaoDeCapsParaUsf() {
        UsuarioDTO a = service.save(dto(null, UnidadeAtuacao.CAPS_I, null));
        autenticar(a);
        Long id = usf();
        assertThrows(ValidationException.class, () -> service.save(dto(a.idPublico(), UnidadeAtuacao.USF, id)));
        assertEquals(UnidadeAtuacao.CAPS_I, usuarios.findByIdPublico(a.idPublico()).orElseThrow().getUnidadeAtuacao());
        assertNull(usuarios.findByIdPublico(a.idPublico()).orElseThrow().getUsfAtuacao());
    }

    @Test
    void respostaNuncaExpoeSenha() {
        UsuarioDTO entrada = dto(null, UnidadeAtuacao.CAPS_II, null);
        assertFalse(json.writeValueAsString(entrada).contains("senha"));
        UsuarioDTO salvo = service.save(entrada);
        assertNull(salvo.senha());
        autenticar(salvo);
        assertFalse(json.writeValueAsString(service.findById(salvo.idPublico())).contains("senha"));
    }

    @Test
    void objetoUsfEnviadoNaoDecideAssociacao() {
        UsuarioDTO dto = json.readValue("""
                {"nome":"Teste","email":"teste@test.local","tipoUsuario":"PROFISSIONAL",
                 "unidadeAtuacao":"USF","usfAtuacao":{"id":1,"nomeUsf":"Forjada"},"senha":"senha-teste"}
                """, UsuarioDTO.class);
        assertNull(dto.usfAtuacao());
        assertThrows(ValidationException.class, () -> service.save(dto));
    }

    @Test
    void enumEConverterConservamCodigos() {
        var converter = new UnidadeAtuacaoConverter();
        UnidadeAtuacao[] valores = {UnidadeAtuacao.USF, UnidadeAtuacao.CAPS_AD, UnidadeAtuacao.CAPS_I, UnidadeAtuacao.CAPS_II};
        assertEquals(4, UnidadeAtuacao.values().length);
        for (int i = 0; i < valores.length; i++) {
            assertEquals(i + 1, converter.convertToDatabaseColumn(valores[i]));
            assertEquals(valores[i], converter.convertToEntityAttribute(i + 1));
        }
    }

    @Test
    void removerUsuarioNaoRemoveUsfIndependente() {
        Long id = usf();
        UsuarioDTO salvo = service.save(dto(null, UnidadeAtuacao.USF, id));
        autenticar(salvo);
        service.removerUsuario(salvo.idPublico());
        usuarios.flush();
        assertTrue(usfs.existsById(id));
    }
}
