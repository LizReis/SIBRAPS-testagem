package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.GrupoTerapeuticoRepository;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.SessaoGrupoParticipanteRepository;
import com.pet.buscaativa.repositories.SessaoGrupoRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.AutorizacaoVinculoAssistencialService;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.impl.LeituraClinicaLocalService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

/**
 * Integração com persistência JPA/H2 REAL. A autenticação/unidade é simulada
 * estritamente no EscopoUnidadeService para caracterizar este autorizador,
 * não a cadeia JWT/HTTP. Rotas legadas NÃO usam este serviço nesta etapa.
 */
@SpringBootTest
@Transactional
class AutorizacaoVinculoPersistenciaTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;
    @MockitoBean EscopoUnidadeService escopo;

    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired AgendamentoRepository agendas;
    @Autowired HistoricoPacienteRepository historicos;
    @Autowired GrupoTerapeuticoRepository grupos;
    @Autowired SessaoGrupoRepository sessoes;
    @Autowired SessaoGrupoParticipanteRepository participantes;
    @Autowired AutorizacaoVinculoAssistencialService autorizacao;
    @Autowired LeituraClinicaLocalService leitura;

    private Paciente pessoa() {
        Paciente p = new Paciente();
        p.setNome("Pessoa sintetica com identidade global");
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        p.setCapsReferencia(CapsEnum.CAPS_II); // referencia NAO autoriza CAPS_II
        return pacientes.saveAndFlush(p);
    }

    private Usuario profissional(UnidadeAtuacao unidade) {
        Usuario u = new Usuario();
        u.setNome("Profissional sintetico " + unidade);
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("somente-para-teste");
        u.setTipoUsuario(TipoUsuario.PROFISSIONAL);
        u.setUnidadeAtuacao(unidade);
        return usuarios.saveAndFlush(u);
    }

    private void autenticar(Usuario ator) {
        when(escopo.usuarioLogado()).thenReturn(ator);
    }

    @Test
    void consultaInternaPorVinculoRetornaSomenteFatosDaUnidadeAutenticada() {
        Paciente pessoa = pessoa();
        Usuario ad = profissional(UnidadeAtuacao.CAPS_AD);
        Usuario ii = profissional(UnidadeAtuacao.CAPS_II);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(pessoa, UnidadeAtuacao.CAPS_AD));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(pessoa, UnidadeAtuacao.CAPS_II));

        Agendamento aAd = novoAgendamento(pessoa, ad);
        Agendamento aIi = novoAgendamento(pessoa, ii);
        HistoricoPaciente hAd = novoHistorico(aAd);
        HistoricoPaciente hIi = novoHistorico(aIi);
        Agendamento legado = new Agendamento();
        legado.setPaciente(pessoa);
        legado.setUsuario(ad);
        legado.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        legado.setDataAgendamento(LocalDate.of(2026, 12, 21));
        legado.setHoraAtendimento(LocalTime.of(11, 0));
        legado = agendas.saveAndFlush(legado);

        autenticar(ad);
        var localAd = leitura.consultarIdentificadoresLocais(pessoa.getIdPublico());
        assertEquals(java.util.List.of(aAd.getId()), localAd.agendamentos());
        assertEquals(java.util.List.of(hAd.getId()), localAd.historicos());
        assertFalse(localAd.agendamentos().contains(legado.getId()));
        assertFalse(localAd.historicos().contains(hIi.getId()));

        autenticar(ii);
        var localIi = leitura.consultarIdentificadoresLocais(pessoa.getIdPublico());
        assertEquals(java.util.List.of(aIi.getId()), localIi.agendamentos());
        assertEquals(java.util.List.of(hIi.getId()), localIi.historicos());
        assertFalse(localIi.agendamentos().contains(aAd.getId()));
    }

    @Test
    void leituraInternaNaoDevolveIdsDeRemarcacoesComRaizExternaOuIncerta() {
        Paciente pessoa = pessoa();
        Usuario ad = profissional(UnidadeAtuacao.CAPS_AD);
        Usuario ii = profissional(UnidadeAtuacao.CAPS_II);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(pessoa, UnidadeAtuacao.CAPS_AD));

        Agendamento raizExterna = novoAgendamento(pessoa, ii);
        Agendamento remarcacaoMista = novoAgendamento(pessoa, ad);
        remarcacaoMista.setAgendamentoOriginal(raizExterna);
        agendas.saveAndFlush(remarcacaoMista);
        HistoricoPaciente historicoMisto = novoHistorico(remarcacaoMista);

        Agendamento semOrigem = new Agendamento();
        semOrigem.setPaciente(pessoa);
        semOrigem.setUsuario(ad);
        semOrigem.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        semOrigem.setDataAgendamento(LocalDate.of(2026, 12, 22));
        semOrigem.setHoraAtendimento(LocalTime.of(9, 30));
        semOrigem = agendas.saveAndFlush(semOrigem);
        Agendamento filhoComRaizIncerta = novoAgendamento(pessoa, ad);
        filhoComRaizIncerta.setAgendamentoOriginal(semOrigem);
        agendas.saveAndFlush(filhoComRaizIncerta);
        HistoricoPaciente historicoIncerto = novoHistorico(filhoComRaizIncerta);

        Agendamento fatoLocal = novoAgendamento(pessoa, ad);
        HistoricoPaciente historicoLocal = novoHistorico(fatoLocal);

        autenticar(ad);
        var ids = leitura.consultarIdentificadoresLocais(pessoa.getIdPublico());
        assertEquals(java.util.List.of(fatoLocal.getId()), ids.agendamentos());
        assertEquals(java.util.List.of(historicoLocal.getId()), ids.historicos());
        assertFalse(ids.agendamentos().contains(remarcacaoMista.getId()));
        assertFalse(ids.agendamentos().contains(filhoComRaizIncerta.getId()));
        assertFalse(ids.historicos().contains(historicoMisto.getId()));
        assertFalse(ids.historicos().contains(historicoIncerto.getId()));
        assertNull(agendas.findById(semOrigem.getId())
                .orElseThrow().getUnidadeOrigemChave());
        assertEquals("CAPS_II", agendas.findById(raizExterna.getId())
                .orElseThrow().getUnidadeOrigemChave());
        assertEquals(1L, pacientes.count());
    }

    @Test
    void consultaInternaSemVinculoNaoRetornaIdsMesmoComReferenciaCadastral() {
        Paciente pessoa = pessoa(); // referencia CAPS II nao constitui vinculo
        Usuario ii = profissional(UnidadeAtuacao.CAPS_II);
        novoAgendamento(pessoa, ii);
        autenticar(ii);

        assertThrows(ResourceNotFoundException.class,
                () -> leitura.consultarIdentificadoresLocais(pessoa.getIdPublico()));
    }

    @Test
    void pacienteComReferenciaCapsIIENotFoundSemVinculoMesmoComAgendaDeCapsAd() {
        Paciente pessoa = pessoa();
        Usuario ad = profissional(UnidadeAtuacao.CAPS_AD);
        Usuario ii = profissional(UnidadeAtuacao.CAPS_II);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(pessoa, UnidadeAtuacao.CAPS_AD));

        Agendamento atendimento = novoAgendamento(pessoa, ad);
        autenticar(ad);
        assertDoesNotThrow(() -> autorizacao.validarAgendamentoLocal(atendimento));

        autenticar(ii);
        assertThrows(ResourceNotFoundException.class,
                () -> autorizacao.exigirVinculoLocal(pessoa.getIdPublico()));
        assertThrows(ResourceNotFoundException.class,
                () -> autorizacao.validarAgendamentoLocal(atendimento));
        assertEquals(1, vinculos.findByPacienteId(pessoa.getId()).size());
    }

    @Test
    void pacienteComVinculoEmDoisCapsNaoCompartilhaAgendasNemHistoricos() {
        Paciente pessoa = pessoa();
        Usuario ad = profissional(UnidadeAtuacao.CAPS_AD);
        Usuario ii = profissional(UnidadeAtuacao.CAPS_II);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(pessoa, UnidadeAtuacao.CAPS_AD));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(pessoa, UnidadeAtuacao.CAPS_II));

        Agendamento agendaAd = novoAgendamento(pessoa, ad);
        Agendamento agendaIi = novoAgendamento(pessoa, ii);
        HistoricoPaciente historiaAd = novoHistorico(agendaAd);

        autenticar(ad);
        assertDoesNotThrow(() -> autorizacao.validarAgendamentoLocal(agendaAd));
        assertThrows(ResourceNotFoundException.class, () -> autorizacao.validarAgendamentoLocal(agendaIi));
        assertDoesNotThrow(() -> autorizacao.validarHistoricoLocal(historiaAd));

        autenticar(ii);
        assertDoesNotThrow(() -> autorizacao.validarAgendamentoLocal(agendaIi));
        assertThrows(ResourceNotFoundException.class, () -> autorizacao.validarAgendamentoLocal(agendaAd));
        assertThrows(ResourceNotFoundException.class, () -> autorizacao.validarHistoricoLocal(historiaAd));

        assertEquals(1L, pacientes.count());
        assertEquals(2, vinculos.findByPacienteId(pessoa.getId()).size());
    }

    @Test
    void pacienteSemParticipacaoEmSessaoNaoTemAcessoMesmoComVinculoLocal() {
        Paciente pessoa = pessoa();
        Paciente outroPaciente = pessoa();
        Usuario ad = profissional(UnidadeAtuacao.CAPS_AD);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(pessoa, UnidadeAtuacao.CAPS_AD));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(outroPaciente, UnidadeAtuacao.CAPS_AD));

        GrupoTerapeutico grupo = new GrupoTerapeutico();
        grupo.setTema("Grupo sintetico");
        grupo.setCoordenador(ad);
        grupo.registrarUnidadeDeOrigem(ad);
        grupo = grupos.saveAndFlush(grupo);

        SessaoGrupo sessao = new SessaoGrupo();
        sessao.setGrupo(grupo);
        sessao.setDataSessao(LocalDate.of(2026, 12, 10));
        sessao.setHorario(LocalTime.of(10, 0));
        sessao.setStatus(StatusSessaoGrupo.AGENDADA);
        sessao.registrarUnidadeDeOrigemDoGrupo();
        sessao = sessoes.saveAndFlush(sessao);

        SessaoGrupoParticipante participacao = new SessaoGrupoParticipante();
        participacao.setPaciente(pessoa);
        participacao.setSessaoGrupo(sessao);
        participantes.saveAndFlush(participacao);

        autenticar(ad);
        SessaoGrupo sessaoRegistrada = sessao;
        assertDoesNotThrow(() -> autorizacao.validarSessaoLocal(sessaoRegistrada, pessoa));
        assertThrows(ResourceNotFoundException.class,
                () -> autorizacao.validarSessaoLocal(sessaoRegistrada, outroPaciente));
    }

    @Test
    void fatoLegadoSemOrigemNaoFicaVisivelPorTerVinculoNovo() {
        Paciente pessoa = pessoa();
        Usuario ad = profissional(UnidadeAtuacao.CAPS_AD);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(pessoa, UnidadeAtuacao.CAPS_AD));
        Agendamento legado = new Agendamento();
        legado.setPaciente(pessoa);
        legado.setUsuario(ad);
        legado.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        legado.setDataAgendamento(LocalDate.of(2026, 12, 15));
        legado.setHoraAtendimento(LocalTime.of(10, 0));
        legado = agendas.saveAndFlush(legado); // simulacao de registro legado sem origem

        autenticar(ad);
        Agendamento antigo = legado;
        assertNull(antigo.getUnidadeOrigemChave());
        assertThrows(ResourceNotFoundException.class, () -> autorizacao.validarAgendamentoLocal(antigo));
    }

    private Agendamento novoAgendamento(Paciente pessoa, Usuario profissional) {
        Agendamento a = new Agendamento();
        a.setPaciente(pessoa);
        a.setUsuario(profissional);
        a.registrarUnidadeDeOrigem(profissional);
        a.setDataAgendamento(LocalDate.of(2026, 12, 20));
        a.setHoraAtendimento(LocalTime.of(9, 30));
        a.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        return agendas.saveAndFlush(a);
    }

    private HistoricoPaciente novoHistorico(Agendamento agendamento) {
        HistoricoPaciente h = new HistoricoPaciente();
        h.setPaciente(agendamento.getPaciente());
        h.setAgendamento(agendamento);
        h.setProfissional(agendamento.getUsuario());
        h.setTipo(TipoEventoHistoricoPaciente.CONSULTA_AGENDADA);
        h.setOcorridoEm(LocalDateTime.of(2026, 12, 20, 9, 30));
        h.registrarOrigemDoAgendamento(agendamento);
        return historicos.saveAndFlush(h);
    }
}
