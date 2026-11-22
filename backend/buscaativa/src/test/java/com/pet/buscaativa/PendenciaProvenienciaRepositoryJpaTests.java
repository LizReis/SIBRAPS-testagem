package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.PendenciaRevisaoProvenienciaRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.impl.SusApiAdapter;

/**
 * Integra a consulta derivada Spring Data/JPA com H2 de TESTE em memoria.
 * Nao chama API, nao cria pendencias por servico e nao migra registros reais.
 * As linhas sinteticas sao inseridas diretamente pelo fixture de teste.
 */
@SpringBootTest
@Transactional
class PendenciaProvenienciaRepositoryJpaTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;

    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired PendenciaRevisaoProvenienciaRepository pendencias;
    @Autowired JdbcTemplate jdbc;

    @Test
    void metodosSpringDataJpaRespeitamPacienteEUnidadeInclusiveNoDetalhe() {
        Paciente pessoa = new Paciente();
        pessoa.setNome("Paciente sintetico de dupla vinculacao");
        pessoa.setCpf(UUID.randomUUID().toString());
        pessoa.setCns(UUID.randomUUID().toString());
        pessoa = pacientes.saveAndFlush(pessoa);

        Usuario solicitante = new Usuario();
        solicitante.setNome("Revisor sintetico sem rota HTTP");
        solicitante.setEmail(UUID.randomUUID() + "@test.local");
        solicitante.setSenha("apenas-teste");
        solicitante.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        solicitante.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        solicitante = usuarios.saveAndFlush(solicitante);

        VinculoAssistencial ad = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(pessoa, UnidadeAtuacao.CAPS_AD));
        VinculoAssistencial ii = vinculos.saveAndFlush(
                VinculoAssistencial.paraCaps(pessoa, UnidadeAtuacao.CAPS_II));

        UUID idAd = UUID.randomUUID();
        UUID idIi = UUID.randomUUID();
        inserirPendenciaSintetica(idAd, ad, pessoa.getId(), solicitante.getId(), 101L);
        inserirPendenciaSintetica(idIi, ii, pessoa.getId(), solicitante.getId(), 102L);

        var paginaAd = pendencias.findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                pessoa.getId(), "CAPS_AD", Estado.PENDENTE, PageRequest.of(0, 10));
        var paginaIi = pendencias.findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                pessoa.getId(), "CAPS_II", Estado.PENDENTE, PageRequest.of(0, 10));
        assertEquals(1, paginaAd.getTotalElements());
        assertEquals(1, paginaIi.getTotalElements());
        assertEquals(idAd, paginaAd.getContent().get(0).getId());
        assertEquals(idIi, paginaIi.getContent().get(0).getId());

        assertTrue(pendencias.findByIdAndPacienteIdAndUnidadeChave(
                idAd, pessoa.getId(), "CAPS_AD").isPresent());
        assertFalse(pendencias.findByIdAndPacienteIdAndUnidadeChave(
                idIi, pessoa.getId(), "CAPS_AD").isPresent());
        assertFalse(pendencias.findByIdAndPacienteIdAndUnidadeChave(
                idAd, pessoa.getId(), "CAPS_II").isPresent());
        assertFalse(pendencias.findByIdAndPacienteIdAndUnidadeChave(
                idAd, pessoa.getId() + 999, "CAPS_AD").isPresent());
        assertTrue(pendencias.findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                pessoa.getId(), "CAPS_I", Estado.PENDENTE, PageRequest.of(0, 10)).isEmpty());
    }

    private void inserirPendenciaSintetica(UUID id, VinculoAssistencial vinculo,
            Long pacienteId, Long solicitanteId, Long registroId) {
        jdbc.update("""
                insert into tb_pendencia_revisao_proveniencia(
                    id, vinculo_id, paciente_id, unidade_chave, tipo_registro,
                    registro_id, situacao_evidencia, justificativa, solicitante_id,
                    perfil_solicitante, solicitado_em, estado)
                values (?, ?, ?, ?, 'AGENDAMENTO', ?,
                    'REVISAR_ORIGEM_DESCONHECIDA',
                    'Solicitacao sintetica para revisao local comprovada',
                    ?, 'ADMINISTRADOR', CURRENT_TIMESTAMP, 'PENDENTE')
                """,
                id, vinculo.getId(), pacienteId, vinculo.getUnidadeChave(),
                registroId, solicitanteId);
    }
}
