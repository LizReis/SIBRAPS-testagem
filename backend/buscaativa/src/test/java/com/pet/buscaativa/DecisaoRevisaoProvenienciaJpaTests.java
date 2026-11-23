package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hibernate.annotations.Immutable;
import org.hibernate.envers.Audited;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.DecisaoRevisaoProvenienciaRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.impl.ConsultaDecisaoRevisaoProvenienciaService;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import static org.mockito.Mockito.when;

/**
 * Mapeamento JPA e consultas V13 em H2 de TESTE e apenas com fixtures
 * sinteticas. INSERT de fixtures e SQL direto, nao simula aprovacao via
 * aplicacao. As garantias SQL de V13 offline sao testadas separadamente.
 */
@SpringBootTest
@Transactional
class DecisaoRevisaoProvenienciaJpaTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;
    @MockitoBean EscopoUnidadeService escopo;

    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired ConsultaDecisaoRevisaoProvenienciaService consultaDecisoes;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired DecisaoRevisaoProvenienciaRepository decisoes;
    @Autowired JdbcTemplate jdbc;

    @Test
    void leituraJpaNaoConfundeDecisoesDoMesmoPacienteEmDoisCaps() {
        Paciente p = new Paciente();
        p.setNome("Paciente sintetico decisao V13 duas unidades");
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        p = pacientes.saveAndFlush(p);

        Usuario revisor = new Usuario();
        revisor.setNome("Administrador sintetico de decisao");
        revisor.setEmail(UUID.randomUUID() + "@test.local");
        revisor.setSenha("somente-teste");
        revisor.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        revisor.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        revisor = usuarios.saveAndFlush(revisor);

        VinculoAssistencial ad = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        VinculoAssistencial ii = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));

        UUID ticketAd = UUID.randomUUID(), ticketIi = UUID.randomUUID();
        UUID idAd = UUID.randomUUID(), idIi = UUID.randomUUID();
        fixture(ticketAd, idAd, ad, revisor, 101L, "REJEITAR");
        fixture(ticketIi, idIi, ii, revisor, 102L, "APROVAR");

        var localAd = decisoes.findByPendenciaIdAndPacienteIdAndUnidadeChave(
                ticketAd, p.getId(), "CAPS_AD").orElseThrow();
        var historicoAd = decisoes.listarPorPacienteEUnidade(
                p.getId(), "CAPS_AD", PageRequest.of(0, 10));
        var historicoIi = decisoes.listarPorPacienteEUnidade(
                p.getId(), "CAPS_II", PageRequest.of(0, 10));
        assertEquals(1, historicoAd.getTotalElements());
        assertEquals(idAd, historicoAd.getContent().get(0).getId());
        assertEquals(1, historicoIi.getTotalElements());
        assertEquals(idIi, historicoIi.getContent().get(0).getId());
        assertEquals(0, decisoes.listarPorPacienteEUnidade(
                p.getId(), "CAPS_I", PageRequest.of(0, 10)).getTotalElements());
        assertEquals(0, decisoes.listarPorPacienteEUnidade(
                p.getId() + 999L, "CAPS_AD", PageRequest.of(0, 10)).getTotalElements());
        assertEquals(idAd, localAd.getId());
        assertEquals(DecisaoRevisaoProveniencia.Decisao.REJEITAR, localAd.getDecisao());
        assertEquals("CAPS_AD", localAd.getUnidadeChave());

        assertEquals(idIi, decisoes.findByIdAndPacienteIdAndUnidadeChave(
                idIi, p.getId(), "CAPS_II").orElseThrow().getId());
        assertTrue(decisoes.findByPendenciaIdAndPacienteIdAndUnidadeChave(
                ticketIi, p.getId(), "CAPS_AD").isEmpty());
        assertTrue(decisoes.findByIdAndPacienteIdAndUnidadeChave(
                idAd, p.getId(), "CAPS_II").isEmpty());
        assertTrue(decisoes.findByIdAndPacienteIdAndUnidadeChave(
                idAd, p.getId() + 999L, "CAPS_AD").isEmpty());
    }

    private void fixture(UUID ticket, UUID decisao, VinculoAssistencial vinculo,
            Usuario revisor, Long registro, String resultado) {
        jdbc.update("""
            insert into tb_pendencia_revisao_proveniencia(
                id, vinculo_id, paciente_id, unidade_chave, tipo_registro,
                registro_id, situacao_evidencia, justificativa, solicitante_id,
                perfil_solicitante, solicitado_em, estado)
            values (?, ?, ?, ?, 'AGENDAMENTO', ?,
                'COMPATIVEL_SOMENTE_PARA_CONFERENCIA',
                'Solicitacao sintetica para revisao documental local',
                ?, 'ADMINISTRADOR', CURRENT_TIMESTAMP, 'PENDENTE')
            """, ticket, vinculo.getId(), vinculo.getPaciente().getId(),
                vinculo.getUnidadeChave(), registro, revisor.getId());

        jdbc.update("""
            insert into tb_decisao_revisao_proveniencia(
                id, pendencia_id, paciente_id, unidade_chave, decisao,
                justificativa, revisor_id, perfil_revisor, decidida_em)
            values (?, ?, ?, ?, ?, 
                'Decisao sintetica com documento clinico revisado pela equipe',
                ?, 'ADMINISTRADOR', CURRENT_TIMESTAMP)
            """, decisao, ticket, vinculo.getPaciente().getId(),
                vinculo.getUnidadeChave(), resultado, revisor.getId());
    }

    @Test
    void paginacaoEstavelNaoDuplicaDecisoesNemMisturaUnidadesOuPacientes() {
        Paciente p = new Paciente();
        p.setNome("Paciente sintetico paginacao local");
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        p = pacientes.saveAndFlush(p);

        Paciente outro = new Paciente();
        outro.setNome("Outro paciente sintetico no mesmo CAPS");
        outro.setCpf(UUID.randomUUID().toString());
        outro.setCns(UUID.randomUUID().toString());
        outro = pacientes.saveAndFlush(outro);

        Usuario admin = new Usuario();
        admin.setNome("Administrador sintetico paginacao");
        admin.setEmail(UUID.randomUUID() + "@test.local");
        admin.setSenha("somente-teste");
        admin.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        admin.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        admin = usuarios.saveAndFlush(admin);

        var ad = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        var ii = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));
        var adOutro = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(outro, UnidadeAtuacao.CAPS_AD));

        Set<UUID> idsEsperados = new java.util.HashSet<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            idsEsperados.add(id);
            fixture(UUID.randomUUID(), id, ad, admin, 301L + i,
                    i % 2 == 0 ? "APROVAR" : "REJEITAR");
        }
        UUID idIi = UUID.randomUUID();
        UUID idOutro = UUID.randomUUID();
        fixture(UUID.randomUUID(), idIi, ii, admin, 401L, "APROVAR");
        fixture(UUID.randomUUID(), idOutro, adOutro, admin, 501L, "REJEITAR");

        java.util.List<UUID> primeiraLeitura = new java.util.ArrayList<>();
        for (int indice = 0; indice < 3; indice++) {
            var pagina = decisoes.listarPorPacienteEUnidade(
                    p.getId(), "CAPS_AD", PageRequest.of(indice, 2));
            assertEquals(5, pagina.getTotalElements());
            assertEquals(3, pagina.getTotalPages());
            assertEquals(indice == 2 ? 1 : 2, pagina.getNumberOfElements());
            primeiraLeitura.addAll(pagina.getContent().stream()
                    .map(DecisaoRevisaoProveniencia::getId).toList());
        }
        assertEquals(5, primeiraLeitura.size());
        assertEquals(idsEsperados, new java.util.HashSet<>(primeiraLeitura));
        assertFalse(primeiraLeitura.contains(idIi));
        assertFalse(primeiraLeitura.contains(idOutro));
        assertTrue(decisoes.listarPorPacienteEUnidade(
                p.getId(), "CAPS_AD", PageRequest.of(3, 2)).isEmpty());

        // Reconsultar paginas com os mesmos dados deve preservar a sequencia
        // mesmo se os registros tiverem instantes de decisao iguais.
        java.util.List<UUID> segundaLeitura = new java.util.ArrayList<>();
        for (int indice = 0; indice < 3; indice++)
            segundaLeitura.addAll(decisoes.listarPorPacienteEUnidade(
                    p.getId(), "CAPS_AD", PageRequest.of(indice, 2))
                    .getContent().stream().map(DecisaoRevisaoProveniencia::getId).toList());
        assertEquals(primeiraLeitura, segundaLeitura);

        var outroCaps = decisoes.listarPorPacienteEUnidade(
                p.getId(), "CAPS_II", PageRequest.of(0, 2));
        assertEquals(1, outroCaps.getTotalElements());
        assertEquals(idIi, outroCaps.getContent().get(0).getId());
        var outroPaciente = decisoes.listarPorPacienteEUnidade(
                outro.getId(), "CAPS_AD", PageRequest.of(0, 2));
        assertEquals(1, outroPaciente.getTotalElements());
        assertEquals(idOutro, outroPaciente.getContent().get(0).getId());
    }

    @Test
    void decisoesEmDuasUsfsNaoVazamMesmoComIdentidadeGlobalCompartilhada() {
        Paciente p = new Paciente();
        p.setNome("Paciente sintetico com duas USFs");
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        p = pacientes.saveAndFlush(p);

        UsfReferencia usfA = new UsfReferencia();
        usfA.setNomeUsf("USF A sintetica");
        usfA.setCnes(UUID.randomUUID().toString());
        usfA = usfs.saveAndFlush(usfA);
        UsfReferencia usfB = new UsfReferencia();
        usfB.setNomeUsf("USF B sintetica");
        usfB.setCnes(UUID.randomUUID().toString());
        usfB = usfs.saveAndFlush(usfB);
        UsfReferencia usfSemVinculo = new UsfReferencia();
        usfSemVinculo.setNomeUsf("USF sem vinculo sintetica");
        usfSemVinculo.setCnes(UUID.randomUUID().toString());
        usfSemVinculo = usfs.saveAndFlush(usfSemVinculo);

        Usuario adminA = new Usuario();
        adminA.setNome("Admin USF A sintetico");
        adminA.setEmail(UUID.randomUUID() + "@test.local");
        adminA.setSenha("somente-teste");
        adminA.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        adminA.setUnidadeAtuacao(UnidadeAtuacao.USF);
        adminA.setUsfAtuacao(usfA);
        adminA = usuarios.saveAndFlush(adminA);

        Usuario adminB = new Usuario();
        adminB.setNome("Admin USF B sintetico");
        adminB.setEmail(UUID.randomUUID() + "@test.local");
        adminB.setSenha("somente-teste");
        adminB.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        adminB.setUnidadeAtuacao(UnidadeAtuacao.USF);
        adminB.setUsfAtuacao(usfB);
        adminB = usuarios.saveAndFlush(adminB);

        Usuario adminSemVinculo = new Usuario();
        adminSemVinculo.setNome("Admin USF sem vinculo sintetico");
        adminSemVinculo.setEmail(UUID.randomUUID() + "@test.local");
        adminSemVinculo.setSenha("somente-teste");
        adminSemVinculo.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        adminSemVinculo.setUnidadeAtuacao(UnidadeAtuacao.USF);
        adminSemVinculo.setUsfAtuacao(usfSemVinculo);
        adminSemVinculo = usuarios.saveAndFlush(adminSemVinculo);

        var vinculoA = vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, usfA));
        var vinculoB = vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, usfB));
        assertNotEquals(vinculoA.getUnidadeChave(), vinculoB.getUnidadeChave());
        assertEquals(ChaveUnidadeAssistencial.daUsf(usfA), vinculoA.getUnidadeChave());
        assertEquals(ChaveUnidadeAssistencial.daUsf(usfB), vinculoB.getUnidadeChave());

        UUID ticketA1 = UUID.randomUUID();
        UUID ticketA2 = UUID.randomUUID();
        UUID ticketB = UUID.randomUUID();
        UUID idA1 = UUID.randomUUID();
        UUID idA2 = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        fixture(ticketA1, idA1, vinculoA, adminA, 601L, "APROVAR");
        fixture(ticketA2, idA2, vinculoA, adminA, 602L, "REJEITAR");
        fixture(ticketB, idB, vinculoB, adminB, 603L, "REJEITAR");

        when(escopo.usuarioLogado()).thenReturn(adminA);
        var paginaA0 = consultaDecisoes.listar(p.getIdPublico(), PageRequest.of(0, 1));
        var paginaA1 = consultaDecisoes.listar(p.getIdPublico(), PageRequest.of(1, 1));
        assertEquals(2, paginaA0.getTotalElements());
        assertEquals(2, paginaA0.getTotalPages());
        assertEquals(2, paginaA1.getTotalElements());
        Set<UUID> idsA = Set.of(paginaA0.getContent().get(0).id(),
                paginaA1.getContent().get(0).id());
        assertEquals(Set.of(idA1, idA2), idsA);
        assertEquals(idA1, consultaDecisoes.detalhar(p.getIdPublico(), ticketA1).id());
        assertThrows(ResourceNotFoundException.class,
                () -> consultaDecisoes.detalhar(p.getIdPublico(), ticketB));

        when(escopo.usuarioLogado()).thenReturn(adminB);
        var paginaB = consultaDecisoes.listar(p.getIdPublico(), PageRequest.of(0, 10));
        assertEquals(1, paginaB.getTotalElements());
        assertEquals(idB, paginaB.getContent().get(0).id());
        assertEquals(idB, consultaDecisoes.detalhar(p.getIdPublico(), ticketB).id());
        assertThrows(ResourceNotFoundException.class,
                () -> consultaDecisoes.detalhar(p.getIdPublico(), ticketA2));

        when(escopo.usuarioLogado()).thenReturn(adminSemVinculo);
        assertThrows(ResourceNotFoundException.class,
                () -> consultaDecisoes.listar(p.getIdPublico(), PageRequest.of(0, 10)));
        assertThrows(ResourceNotFoundException.class,
                () -> consultaDecisoes.detalhar(p.getIdPublico(), ticketB));

        // Nem a leitura do historico nem a paginacao modificam registros V13.
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from tb_decisao_revisao_proveniencia where paciente_id=?",
                Integer.class, p.getId()));
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia where paciente_id=? and estado='PENDENTE'",
                Integer.class, p.getId()));
    }

    @Test
    void contratoNaoPermiteEscritaEEntidadeEImutavelEAuditada() {
        var metodos = Arrays.stream(DecisaoRevisaoProvenienciaRepository.class.getDeclaredMethods())
                .map(m -> m.getName()).collect(Collectors.toSet());
        assertEquals(Set.of("findByIdAndPacienteIdAndUnidadeChave",
                "findByPendenciaIdAndPacienteIdAndUnidadeChave",
                "listarPorPacienteEUnidade"), metodos);
        assertTrue(DecisaoRevisaoProveniencia.class.isAnnotationPresent(Immutable.class));
        assertTrue(DecisaoRevisaoProveniencia.class.isAnnotationPresent(Audited.class));
        assertEquals(0, Arrays.stream(DecisaoRevisaoProveniencia.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers())
                        && m.getName().startsWith("set")).count());
    }
}
