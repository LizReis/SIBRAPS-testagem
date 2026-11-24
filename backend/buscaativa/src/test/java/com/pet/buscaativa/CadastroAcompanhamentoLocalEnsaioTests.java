package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.pet.buscaativa.alertas.EmailService;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.PacienteDTO;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.impl.CadastroAcompanhamentoLocalEnsaioService;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import jakarta.persistence.EntityManager;

@ActiveProfiles("ensaio-estado-local")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:cadastro-local-ensaio-63;DB_CLOSE_DELAY=-1")
class CadastroAcompanhamentoLocalEnsaioTests extends SecurityTestSupport {
    @Autowired CadastroAcompanhamentoLocalEnsaioService cadastro;
    @Autowired PacienteRepository pacientes;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;
    @Autowired DataSource fonte;
    @MockitoBean SusApiAdapter sus;
    @MockitoBean EmailService email;

    private record Cenario(Usuario caps, Usuario usf, UsfReferencia territorial) {}

    @Test
    void cadastroRealEConfirmacaoCriamEvidenciaLocalSemDuplicarIdentidade() {
        var c = tx.execute(t -> preparar());
        var dados = dados(c, "12312312312", "100000000000123", c.caps().getIdPublico());
        var novo = como(c.caps(), () -> cadastro.cadastrarNovo(dados, false));
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertThrows(ValidationException.class, () -> como(c.usf(), () -> cadastro.confirmarEVincular(
                novo.cpf(), novo.cns(), "Nome divergente", novo.nomeMae(), novo.dataNascimento(),
                TipoAcompanhamento.AMBOS, null)));
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
        var segundo = como(c.usf(), () -> cadastro.confirmarEVincular(novo.cpf(), novo.cns(),
                novo.nome(), novo.nomeMae(), novo.dataNascimento(), TipoAcompanhamento.AMBOS, c.usf().getIdPublico()));
        assertEquals(novo.idPublico(), segundo.pacienteIdPublico());
        tx.executeWithoutResult(t -> {
            var pessoa = pacientes.findByIdPublico(novo.idPublico()).orElseThrow();
            var locais = vinculos.findByPacienteId(pessoa.getId());
            assertEquals(2, locais.size());
            var a = locais.stream().filter(v -> v.getUnidadeChave().equals("CAPS_AD")).findFirst().orElseThrow();
            var b = locais.stream().filter(v -> v.getIdPublico().equals(segundo.vinculoLocalIdPublico())).findFirst().orElseThrow();
            assertEquals(TipoAcompanhamento.INDIVIDUAL, a.getTipoAcompanhamento());
            assertEquals(TipoAcompanhamento.AMBOS, b.getTipoAcompanhamento());
            assertEquals(c.caps().getId(), a.getProfissionalReferencia().getId());
            assertEquals(c.usf().getId(), b.getProfissionalReferencia().getId());
            for (var local : locais) {
                assertEquals(0, local.getCountFaltas());
                assertFalse(local.getIndicadoresPendentes());
                assertEquals(1L, em.createQuery("select count(e) from EventoEstadoClinicoVinculo e where e.vinculo.id = :id",
                        Long.class).setParameter("id", local.getId()).getSingleResult());
            }
        });
        var depois = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertEquals(antes.get("TB_PACIENTE"), depois.get("TB_PACIENTE"));
        assertEquals(antes.get("TB_PACIENTE_AUD"), depois.get("TB_PACIENTE_AUD"));
        var repetido = como(c.usf(), () -> cadastro.confirmarEVincular(novo.cpf(), novo.cns(),
                novo.nome(), novo.nomeMae(), novo.dataNascimento(), TipoAcompanhamento.INDIVIDUAL, null));
        assertEquals(segundo, repetido);
        assertEquals(depois, LegacyMigrationFixture.snapshot(fonte, colunas));
        verifyNoInteractions(email);
    }

    @Test
    void falhaNaInicializacaoReverteCadastroNovo() {
        var c = tx.execute(t -> preparar());
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        var dados = dados(c, "23423423423", "100000000000234", c.usf().getIdPublico());
        assertThrows(ResourceNotFoundException.class, () -> como(c.caps(), () -> cadastro.cadastrarNovo(dados, false)));
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
        verifyNoInteractions(email);
    }

    private Cenario preparar() {
        var usf = new UsfReferencia();
        usf.setCnes(UUID.randomUUID().toString());
        usf.setNomeUsf("USF de ensaio");
        em.persist(usf);
        return new Cenario(usuario(UnidadeAtuacao.CAPS_AD, null), usuario(UnidadeAtuacao.USF, usf), usf);
    }

    private Usuario usuario(UnidadeAtuacao unidade, UsfReferencia usf) {
        var u = new Usuario();
        u.setNome("Profissional sintético");
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("fixture");
        u.setTipoUsuario(TipoUsuario.PROFISSIONAL);
        u.setUnidadeAtuacao(unidade);
        u.setUsfAtuacao(usf);
        em.persist(u);
        return u;
    }

    private PacienteDTO dados(Cenario c, String cpf, String cns, UUID referencia) {
        return new PacienteDTO(null, "Pessoa sintética " + cpf, "Mãe sintética " + cpf,
                LocalDate.of(1995, 1, 1), null, SexoEnum.FEMININO, RacaCorEnum.PARDA,
                cns, cpf, "75999999999", null, true, TipoAcompanhamento.INDIVIDUAL, null, null,
                c.territorial(), CapsEnum.CAPS_AD, referencia, null);
    }

    private static <T> T como(Usuario ator, Supplier<T> comando) {
        var anterior = SecurityContextHolder.getContext();
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ator, null, ator.getAuthorities()));
        SecurityContextHolder.setContext(contexto);
        try { return comando.get(); }
        finally { SecurityContextHolder.setContext(anterior); }
    }
}
