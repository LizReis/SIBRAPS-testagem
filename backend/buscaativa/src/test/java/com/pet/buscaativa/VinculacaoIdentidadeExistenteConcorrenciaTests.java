package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import com.pet.buscaativa.services.impl.VinculacaoIdentidadeExistenteService;

/**
 * Concorrencia REAL de transacoes com H2 sintetico e SecurityContext por thread.
 * Nao usa rollback por @Transactional no thread do teste: os servicos devem
 * concluir suas transacoes e efetivar os vinculos para serem observados.
 * Nao habilita novo endpoint nem concede acesso a historicos entre unidades.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cadastro-unico-vinculos-concorrentes;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=12000",
        "spring.datasource.hikari.maximum-pool-size=12"
})
class VinculacaoIdentidadeExistenteConcorrenciaTests extends SecurityTestSupport {

    @MockitoBean SusApiAdapter susApi;

    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired VinculacaoIdentidadeExistenteService vinculacao;

    private Paciente pessoa(String cpf, String cns) {
        Paciente p = new Paciente();
        p.setNome("Identidade sintetica de concorrencia");
        p.setCpf(cpf);
        p.setCns(cns);
        p.setCapsReferencia(CapsEnum.CAPS_II); // referencia nao concede acesso
        return pacientes.saveAndFlush(p);
    }

    private Usuario operador(UnidadeAtuacao unidade, TipoUsuario perfil) {
        Usuario u = new Usuario();
        u.setNome("Operador sintetico " + unidade);
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("somente-teste");
        u.setTipoUsuario(perfil);
        u.setUnidadeAtuacao(unidade);
        return usuarios.saveAndFlush(u);
    }

    private Callable<VinculacaoIdentidadeExistenteService.Resultado> tarefa(
            Usuario usuario, String cpf, String cns,
            CountDownLatch prontas, CountDownLatch inicio) {
        return () -> {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            usuario, null, usuario.getAuthorities()));
            try {
                prontas.countDown();
                if (!inicio.await(15, TimeUnit.SECONDS))
                    throw new IllegalStateException("Inicio de teste concorrente excedeu tempo.");
                return vinculacao.vincularPorDocumentos(cpf, cns);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    @Test
    void duasVinculacoesConcorrentesNoMesmoCapsCriamSomenteUmVinculo() throws Exception {
        Paciente p = pessoa("90000000001", "900000000000001");
        Usuario a = operador(UnidadeAtuacao.CAPS_AD, TipoUsuario.ADMINISTRADOR);
        Usuario b = operador(UnidadeAtuacao.CAPS_AD, TipoUsuario.PROFISSIONAL);
        var prontas = new CountDownLatch(2);
        var inicio = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var uma = pool.submit(tarefa(a, p.getCpf(), p.getCns(), prontas, inicio));
            var outra = pool.submit(tarefa(b, p.getCpf(), p.getCns(), prontas, inicio));
            assertTrue(prontas.await(15, TimeUnit.SECONDS));
            inicio.countDown();

            var r1 = uma.get(30, TimeUnit.SECONDS);
            var r2 = outra.get(30, TimeUnit.SECONDS);
            assertEquals(p.getIdPublico(), r1.pacienteIdPublico());
            assertEquals(p.getIdPublico(), r2.pacienteIdPublico());
            assertEquals(r1.vinculoLocalIdPublico(), r2.vinculoLocalIdPublico());
            assertEquals("CAPS_AD", r1.unidadeChave());
            assertEquals("CAPS_AD", r2.unidadeChave());
        }

        assertEquals(1, vinculos.findByPacienteId(p.getId()).size());
        assertEquals(p.getIdPublico(), pacientes.findByCpf(p.getCpf())
                .orElseThrow().getIdPublico());
    }

    @Test
    void duasUnidadesConcorrentesReutilizamIdentidadeMasCriamVinculosDistintos()
            throws Exception {
        Paciente p = pessoa("90000000002", "900000000000002");
        Usuario ad = operador(UnidadeAtuacao.CAPS_AD, TipoUsuario.PROFISSIONAL);
        Usuario ii = operador(UnidadeAtuacao.CAPS_II, TipoUsuario.ADMINISTRADOR);
        var prontas = new CountDownLatch(2);
        var inicio = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var primeira = pool.submit(tarefa(ad, p.getCpf(), null, prontas, inicio));
            var segunda = pool.submit(tarefa(ii, null, p.getCns(), prontas, inicio));
            assertTrue(prontas.await(15, TimeUnit.SECONDS));
            inicio.countDown();

            var rAd = primeira.get(30, TimeUnit.SECONDS);
            var rIi = segunda.get(30, TimeUnit.SECONDS);
            assertEquals(p.getIdPublico(), rAd.pacienteIdPublico());
            assertEquals(p.getIdPublico(), rIi.pacienteIdPublico());
            assertNotEquals(rAd.vinculoLocalIdPublico(), rIi.vinculoLocalIdPublico());
            assertEquals("CAPS_AD", rAd.unidadeChave());
            assertEquals("CAPS_II", rIi.unidadeChave());
        }

        assertEquals(2, vinculos.findByPacienteId(p.getId()).size());
        assertEquals(p.getIdPublico(), pacientes.findByCns(p.getCns())
                .orElseThrow().getIdPublico());
    }
}
