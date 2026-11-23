package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.PendenciaRevisaoProvenienciaRepository;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.impl.FilaRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

/**
 * Fila read-only V12: cobertura com banco de teste e filtro pela unidade
 * canonica autenticada, sem HTTP ou acoes de revisao.
 */
@SpringBootTest
@Transactional
class FilaRevisaoProvenienciaIntegrationTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;
    @MockitoBean EscopoUnidadeService escopo;

    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired FilaRevisaoProvenienciaService fila;
    @Autowired JdbcTemplate jdbc;
    @Autowired PendenciaRevisaoProvenienciaRepository consultasPaciente;

    private Paciente pessoa() {
        Paciente p = new Paciente();
        p.setNome("Identidade sintetica com duas unidades");
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        return pacientes.saveAndFlush(p);
    }

    private Usuario administrador(UnidadeAtuacao unidade, UsfReferencia usf) {
        Usuario u = new Usuario();
        u.setNome("Administrador sintetico");
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("somente-teste");
        u.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        u.setUnidadeAtuacao(unidade);
        u.setUsfAtuacao(usf);
        return usuarios.saveAndFlush(u);
    }

    private void pendencia(UUID id, VinculoAssistencial vinculo, Usuario solicitante,
            long registroId) {
        jdbc.update("""
                insert into tb_pendencia_revisao_proveniencia(
                    id, vinculo_id, paciente_id, unidade_chave, tipo_registro,
                    registro_id, situacao_evidencia, justificativa, solicitante_id,
                    perfil_solicitante, solicitado_em, estado)
                values (?, ?, ?, ?, 'AGENDAMENTO', ?,
                    'COMPATIVEL_SOMENTE_PARA_CONFERENCIA',
                    'Solicitacao sintetica para verificacao da unidade',
                    ?, 'ADMINISTRADOR', CURRENT_TIMESTAMP, 'PENDENTE')
                """, id, vinculo.getId(), vinculo.getPaciente().getId(),
                vinculo.getUnidadeChave(), registroId, solicitante.getId());
    }

    @Test
    void mesmaPessoaEmDoisCapsTemFilasDeRevisaoIndependentes() {
        Paciente p = pessoa();
        Usuario ad = administrador(UnidadeAtuacao.CAPS_AD, null);
        Usuario ii = administrador(UnidadeAtuacao.CAPS_II, null);
        VinculoAssistencial va = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        VinculoAssistencial vi = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));
        UUID adId = UUID.randomUUID(), iiId = UUID.randomUUID();
        pendencia(adId, va, ad, 101L);
        pendencia(iiId, vi, ii, 102L);

        when(escopo.usuarioLogado()).thenReturn(ad);
        var filaAd = fila.listarPendentes(PageRequest.of(0, 10));
        assertEquals(1, filaAd.getTotalElements());
        assertEquals(adId, filaAd.getContent().get(0).id());
        var detalheAd = fila.detalharPendente(adId);
        assertEquals(adId, detalheAd.id());
        assertEquals(101L, detalheAd.registroId());
        assertEquals("Solicitacao sintetica para verificacao da unidade", detalheAd.justificativa());
        assertThrows(ResourceNotFoundException.class, () -> fila.detalharPendente(iiId));

        when(escopo.usuarioLogado()).thenReturn(ii);
        var filaIi = fila.listarPendentes(PageRequest.of(0, 10));
        assertEquals(1, filaIi.getTotalElements());
        assertEquals(iiId, filaIi.getContent().get(0).id());
        assertEquals(p.getId(), filaIi.getContent().get(0).pacienteId());
        assertThrows(ResourceNotFoundException.class, () -> fila.detalharPendente(adId));
        assertEquals(iiId, fila.detalharPendente(iiId).id());
        assertThrows(ResourceNotFoundException.class, () -> fila.detalharPendente(null));
    }

    @Test
    void decisaoImutavelRetiraTicketDaFilaAbertaSemAlterarSolicitacaoV12() {
        Paciente p = pessoa();
        Usuario ad = administrador(UnidadeAtuacao.CAPS_AD, null);
        Usuario ii = administrador(UnidadeAtuacao.CAPS_II, null);
        var vinculoAd = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        var vinculoIi = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));
        UUID abertaAd = UUID.randomUUID();
        UUID decididaAd = UUID.randomUUID();
        UUID abertaIi = UUID.randomUUID();
        pendencia(abertaAd, vinculoAd, ad, 201L);
        pendencia(decididaAd, vinculoAd, ad, 202L);
        pendencia(abertaIi, vinculoIi, ii, 203L);

        when(escopo.usuarioLogado()).thenReturn(ad);
        assertEquals(2, fila.listarPendentes(PageRequest.of(0, 10)).getTotalElements());

        // O evento de decisao V13 nao altera PENDENTE na solicitacao V12.
        jdbc.update("""
                insert into tb_decisao_revisao_proveniencia(
                    id, pendencia_id, paciente_id, unidade_chave, decisao,
                    justificativa, revisor_id, perfil_revisor, decidida_em)
                values (?, ?, ?, 'CAPS_AD', 'REJEITAR',
                    'Documentacao sintetica conferida pelo administrador local',
                    ?, 'ADMINISTRADOR', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), decididaAd, p.getId(), ad.getId());

        var paginaAd = fila.listarPendentes(PageRequest.of(0, 10));
        assertEquals(1, paginaAd.getTotalElements());
        assertEquals(abertaAd, paginaAd.getContent().get(0).id());
        assertThrows(ResourceNotFoundException.class,
                () -> fila.detalharPendente(decididaAd));
        assertEquals(abertaAd, fila.detalharPendente(abertaAd).id());

        var porPaciente = consultasPaciente
                .findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                        p.getId(), "CAPS_AD", Estado.PENDENTE, PageRequest.of(0, 10));
        assertEquals(1, porPaciente.getTotalElements());
        assertEquals(abertaAd, porPaciente.getContent().get(0).getId());
        assertTrue(consultasPaciente
                .findByIdAndPacienteIdAndUnidadeChave(decididaAd, p.getId(), "CAPS_AD")
                .isEmpty());
        assertTrue(consultasPaciente
                .findByIdAndPacienteIdAndUnidadeChave(abertaAd, p.getId(), "CAPS_AD")
                .isPresent());

        // CAPS II continua com sua propria fila, independente da decisao AD.
        when(escopo.usuarioLogado()).thenReturn(ii);
        var paginaIi = fila.listarPendentes(PageRequest.of(0, 10));
        assertEquals(1, paginaIi.getTotalElements());
        assertEquals(abertaIi, paginaIi.getContent().get(0).id());
        assertThrows(ResourceNotFoundException.class, () -> fila.detalharPendente(decididaAd));
        assertEquals("PENDENTE", jdbc.queryForObject(
                "select estado from tb_pendencia_revisao_proveniencia where id=?",
                String.class, decididaAd));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_decisao_revisao_proveniencia where pendencia_id=?",
                Integer.class, decididaAd));
    }

    @Test
    void paginacaoDaFilaAbertaMantemContagemEIsolamentoAposDecisaoV13() {
        Paciente p = pessoa();
        Usuario ad = administrador(UnidadeAtuacao.CAPS_AD, null);
        Usuario ii = administrador(UnidadeAtuacao.CAPS_II, null);
        var vinculoAd = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        var vinculoIi = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));

        java.util.Set<UUID> abertas = new java.util.HashSet<>();
        for (int i = 0; i < 5; i++) {
            UUID ticket = UUID.randomUUID();
            abertas.add(ticket);
            pendencia(ticket, vinculoAd, ad, 610L + i);
        }
        UUID decididaAd = UUID.randomUUID();
        UUID abertaIi = UUID.randomUUID();
        pendencia(decididaAd, vinculoAd, ad, 620L);
        pendencia(abertaIi, vinculoIi, ii, 621L);

        jdbc.update("""
                insert into tb_decisao_revisao_proveniencia(
                    id, pendencia_id, paciente_id, unidade_chave, decisao,
                    justificativa, revisor_id, perfil_revisor, decidida_em)
                values (?, ?, ?, 'CAPS_AD', 'REJEITAR',
                    'Revisao sintetica concluida e registrada na trilha auditavel',
                    ?, 'ADMINISTRADOR', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), decididaAd, p.getId(), ad.getId());

        when(escopo.usuarioLogado()).thenReturn(ad);
        java.util.List<UUID> leitura = new java.util.ArrayList<>();
        for (int indice = 0; indice < 3; indice++) {
            var pagina = fila.listarPendentes(PageRequest.of(indice, 2));
            assertEquals(5, pagina.getTotalElements());
            assertEquals(3, pagina.getTotalPages());
            assertEquals(indice == 2 ? 1 : 2, pagina.getNumberOfElements());
            leitura.addAll(pagina.getContent().stream().map(x -> x.id()).toList());
        }
        assertEquals(5, leitura.size());
        assertEquals(abertas, new java.util.HashSet<>(leitura));
        assertFalse(leitura.contains(decididaAd));
        assertFalse(leitura.contains(abertaIi));
        assertTrue(fila.listarPendentes(PageRequest.of(3, 2)).isEmpty());
        assertThrows(ResourceNotFoundException.class, () -> fila.detalharPendente(decididaAd));

        java.util.List<UUID> repetida = new java.util.ArrayList<>();
        for (int indice = 0; indice < 3; indice++)
            repetida.addAll(fila.listarPendentes(PageRequest.of(indice, 2))
                    .getContent().stream().map(x -> x.id()).toList());
        assertEquals(leitura, repetida);

        var porPaciente = consultasPaciente
                .findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                        p.getId(), "CAPS_AD", Estado.PENDENTE, PageRequest.of(0, 2));
        assertEquals(5, porPaciente.getTotalElements());
        assertEquals(3, porPaciente.getTotalPages());

        when(escopo.usuarioLogado()).thenReturn(ii);
        var filaIi = fila.listarPendentes(PageRequest.of(0, 2));
        assertEquals(1, filaIi.getTotalElements());
        assertEquals(abertaIi, filaIi.getContent().get(0).id());
        assertThrows(ResourceNotFoundException.class, () -> fila.detalharPendente(decididaAd));

        assertEquals("PENDENTE", jdbc.queryForObject(
                "select estado from tb_pendencia_revisao_proveniencia where id=?",
                String.class, decididaAd));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_decisao_revisao_proveniencia where pendencia_id=?",
                Integer.class, decididaAd));
    }

    @Test
    void duasUsfsDistintasNaoCompartilhamFilaMesmoComMesmaIdentidade() {
        Paciente p = pessoa();
        UsfReferencia usfA = new UsfReferencia();
        usfA.setNomeUsf("USF sintetica A");
        usfA.setCnes(UUID.randomUUID().toString());
        usfA = usfs.saveAndFlush(usfA);
        UsfReferencia usfB = new UsfReferencia();
        usfB.setNomeUsf("USF sintetica B");
        usfB.setCnes(UUID.randomUUID().toString());
        usfB = usfs.saveAndFlush(usfB);

        Usuario adminA = administrador(UnidadeAtuacao.USF, usfA);
        Usuario adminB = administrador(UnidadeAtuacao.USF, usfB);
        VinculoAssistencial va = vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, usfA));
        VinculoAssistencial vb = vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, usfB));
        UUID idA = UUID.randomUUID(), idB = UUID.randomUUID();
        pendencia(idA, va, adminA, 103L);
        pendencia(idB, vb, adminB, 104L);
        assertEquals(ChaveUnidadeAssistencial.daUsf(usfA), va.getUnidadeChave());
        assertNotEquals(va.getUnidadeChave(), vb.getUnidadeChave());

        when(escopo.usuarioLogado()).thenReturn(adminA);
        assertEquals(idA, fila.listarPendentes(PageRequest.of(0, 10))
                .getContent().get(0).id());
        assertEquals(idA, fila.detalharPendente(idA).id());
        assertThrows(ResourceNotFoundException.class, () -> fila.detalharPendente(idB));
        when(escopo.usuarioLogado()).thenReturn(adminB);
        assertEquals(idB, fila.listarPendentes(PageRequest.of(0, 10))
                .getContent().get(0).id());
        assertThrows(ResourceNotFoundException.class, () -> fila.detalharPendente(idA));
        assertEquals(idB, fila.detalharPendente(idB).id());
    }

    @Test
    void profissionalRecepcaoEPaginacaoExcessivaNaoConsultamFila() {
        Usuario profissional = new Usuario();
        profissional.setTipoUsuario(TipoUsuario.PROFISSIONAL);
        profissional.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        when(escopo.usuarioLogado()).thenReturn(profissional);
        assertThrows(AccessDeniedException.class,
                () -> fila.listarPendentes(PageRequest.of(0, 10)));
        assertThrows(AccessDeniedException.class,
                () -> fila.detalharPendente(UUID.randomUUID()));

        Usuario recepcao = new Usuario();
        recepcao.setTipoUsuario(TipoUsuario.RECEPCAO);
        recepcao.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        when(escopo.usuarioLogado()).thenReturn(recepcao);
        assertThrows(AccessDeniedException.class,
                () -> fila.listarPendentes(PageRequest.of(0, 10)));
        assertThrows(AccessDeniedException.class,
                () -> fila.detalharPendente(UUID.randomUUID()));

        Usuario administrador = new Usuario();
        administrador.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        administrador.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        when(escopo.usuarioLogado()).thenReturn(administrador);
        assertThrows(IllegalArgumentException.class,
                () -> fila.listarPendentes(PageRequest.of(0, 101)));
        assertThrows(IllegalArgumentException.class,
                () -> fila.listarPendentes(PageRequest.of(0, 10,
                        org.springframework.data.domain.Sort.by("unidadeChave"))));
    }
}
