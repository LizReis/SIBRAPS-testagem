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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.DecisaoRevisaoProvenienciaRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.impl.SusApiAdapter;

/**
 * Mapeamento JPA e consultas V13 em H2 de TESTE e apenas com fixtures
 * sinteticas. INSERT de fixtures e SQL direto, nao simula aprovacao via
 * aplicacao. As garantias SQL de V13 offline sao testadas separadamente.
 */
@SpringBootTest
@Transactional
class DecisaoRevisaoProvenienciaJpaTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;

    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
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
    void contratoNaoPermiteEscritaEEntidadeEImutavelEAuditada() {
        var metodos = Arrays.stream(DecisaoRevisaoProvenienciaRepository.class.getDeclaredMethods())
                .map(m -> m.getName()).collect(Collectors.toSet());
        assertEquals(Set.of("findByIdAndPacienteIdAndUnidadeChave",
                "findByPendenciaIdAndPacienteIdAndUnidadeChave"), metodos);
        assertTrue(DecisaoRevisaoProveniencia.class.isAnnotationPresent(Immutable.class));
        assertTrue(DecisaoRevisaoProveniencia.class.isAnnotationPresent(Audited.class));
        assertEquals(0, Arrays.stream(DecisaoRevisaoProveniencia.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers())
                        && m.getName().startsWith("set")).count());
    }
}
