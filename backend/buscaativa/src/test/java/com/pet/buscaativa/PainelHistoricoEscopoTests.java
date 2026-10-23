package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO.EvolucaoMensalDTO;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.PainelHistoricoService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

@SpringBootTest(properties = "logging.level.org.springframework.security=WARN")
class PainelHistoricoEscopoTests {
    @MockitoBean SusApiAdapter susApi;
    @Autowired TransactionTemplate transacoes;
    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired PainelHistoricoService historico;

    private EvolucaoMensalDTO consultar(Usuario usuario, String filtro) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(usuario, null, usuario.getAuthorities()));
        return historico.reconstruir(List.of(YearMonth.now()), filtro, null, null).get(0);
    }

    @Test
    void revisoesAuditadasRespeitamCapsUsfEIntersecaoDosFiltros() {
        List<Long> idsPacientes = new ArrayList<>();
        List<Long> idsUsuarios = new ArrayList<>();
        List<Long> idsUsfs = new ArrayList<>();
        try {
            List<Usuario> escopos = transacoes.execute(status -> {
                var a = usf(); var b = usf();
                idsUsfs.addAll(List.of(a.getId(), b.getId()));
                var ad = usuario(UnidadeAtuacao.CAPS_AD, null);
                var ua = usuario(UnidadeAtuacao.USF, a);
                var ub = usuario(UnidadeAtuacao.USF, b);
                idsUsuarios.addAll(List.of(ad.getId(), ua.getId(), ub.getId()));
                return List.of(ad, ua, ub);
            });
            var antes = consultar(escopos.get(0), null);
            transacoes.executeWithoutResult(status -> {
                var a = usfs.findById(idsUsfs.get(0)).orElseThrow();
                var b = usfs.findById(idsUsfs.get(1)).orElseThrow();
                idsPacientes.add(paciente(CapsEnum.CAPS_AD, a).getId());
                idsPacientes.add(paciente(CapsEnum.CAPS_AD, b).getId());
                idsPacientes.add(paciente(CapsEnum.CAPS_I, a).getId());
                idsPacientes.add(paciente(CapsEnum.CAPS_II, b).getId());
            });
            var ad = consultar(escopos.get(0), null);
            assertTrue(ad.disponivel());
            assertEquals(antes.vermelhos() + 2, ad.vermelhos());
            assertEquals(antes.verdes(), ad.verdes());
            assertEquals(antes.amarelos(), ad.amarelos());
            assertEquals(2, consultar(escopos.get(1), null).vermelhos());
            assertEquals(2, consultar(escopos.get(2), null).vermelhos());
            var proibido = consultar(escopos.get(0), "CAPS:CAPS_I");
            assertEquals(0, proibido.verdes() + proibido.amarelos() + proibido.vermelhos());
            var outraUsf = consultar(escopos.get(1), "USF:" + escopos.get(2).getUsfAtuacao().getCnes());
            assertEquals(0, outraUsf.verdes() + outraUsf.amarelos() + outraUsf.vermelhos());
        } finally {
            SecurityContextHolder.clearContext();
            transacoes.executeWithoutResult(status -> {
                pacientes.deleteAllById(idsPacientes);
                pacientes.flush();
                usuarios.deleteAllById(idsUsuarios);
                usuarios.flush();
                usfs.deleteAllById(idsUsfs);
            });
        }
    }

    private UsfReferencia usf() {
        var usf = new UsfReferencia();
        usf.setCnes(UUID.randomUUID().toString());
        usf.setNomeUsf("USF auditoria teste");
        return usfs.save(usf);
    }

    private Usuario usuario(UnidadeAtuacao unidade, UsfReferencia usf) {
        var u = new Usuario();
        u.setNome("Auditoria teste"); u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("senha-teste"); u.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        u.setUnidadeAtuacao(unidade); u.setUsfAtuacao(usf);
        return usuarios.save(u);
    }

    private Paciente paciente(CapsEnum caps, UsfReferencia usf) {
        var p = new Paciente();
        p.setNome("Auditoria teste"); p.setStatusPaciente(StatusPaciente.ATIVO);
        p.setCapsReferencia(caps); p.setUsfReferencia(usf);
        p.setTipoAcompanhamento(TipoAcompanhamento.INDIVIDUAL);
        p.setClassificacaoRisco(ClassificacaoRisco.VERMELHO);
        p.setDataNascimento(LocalDate.now().minusYears(30));
        return pacientes.save(p);
    }
}
