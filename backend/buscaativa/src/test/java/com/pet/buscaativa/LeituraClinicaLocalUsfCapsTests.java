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
import com.pet.buscaativa.entities.UsfReferencia;
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
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.impl.LeituraClinicaLocalService;
import com.pet.buscaativa.services.impl.InventarioFrequenciasVinculoService;
import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

/**
 * Caracterizacao com JPA/H2 reais: uma identidade pode ser atendida pela USF,
 * CAPS AD e por duas USFs distintas, sem compartilhar fatos assistenciais.
 *
 * Somente o contexto autenticado da unidade e simulado. Sem endpoint/JWT
 * nesta entrega; testes HTTP e corte de escopo permanecem pendentes.
 */
@SpringBootTest
@Transactional
class LeituraClinicaLocalUsfCapsTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;
    @MockitoBean EscopoUnidadeService escopo;

    @Autowired PacienteRepository pacientes;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired UsuarioRepository usuarios;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired AgendamentoRepository agendamentos;
    @Autowired HistoricoPacienteRepository historicos;
    @Autowired GrupoTerapeuticoRepository grupos;
    @Autowired SessaoGrupoRepository sessoes;
    @Autowired SessaoGrupoParticipanteRepository participantes;
    @Autowired LeituraClinicaLocalService leitura;
    @Autowired InventarioFrequenciasVinculoService inventario;
    @Autowired LinhaDoTempoFrequenciasVinculoService linhaDoTempo;

    private Paciente pessoa() {
        Paciente p = new Paciente();
        p.setNome("Paciente sintetico com identidade unica");
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        p.setCapsReferencia(CapsEnum.CAPS_AD); // Campo meramente informativo.
        return pacientes.saveAndFlush(p);
    }

    private UsfReferencia usf(String nome) {
        UsfReferencia r = new UsfReferencia();
        r.setNomeUsf(nome);
        r.setCnes(UUID.randomUUID().toString());
        return usfs.saveAndFlush(r);
    }

    private Usuario profissional(UnidadeAtuacao unidade, UsfReferencia usf) {
        Usuario u = new Usuario();
        u.setNome("Profissional sintetico " + unidade);
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("nao-utilizada");
        u.setTipoUsuario(TipoUsuario.PROFISSIONAL);
        u.setUnidadeAtuacao(unidade);
        u.setUsfAtuacao(usf);
        return usuarios.saveAndFlush(u);
    }

    private void atuarComo(Usuario u) {
        when(escopo.usuarioLogado()).thenReturn(u);
    }

    private Agendamento agenda(Paciente p, Usuario u, int dia, int hora) {
        Agendamento a = new Agendamento();
        a.setPaciente(p);
        a.setUsuario(u);
        a.registrarUnidadeDeOrigem(u);
        a.setDataAgendamento(LocalDate.of(2026, 12, dia));
        a.setHoraAtendimento(LocalTime.of(hora, 0));
        a.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        return agendamentos.saveAndFlush(a);
    }

    private HistoricoPaciente eventoDeAgenda(Agendamento a) {
        HistoricoPaciente h = new HistoricoPaciente();
        h.setPaciente(a.getPaciente());
        h.setAgendamento(a);
        h.setProfissional(a.getUsuario());
        h.setTipo(TipoEventoHistoricoPaciente.CONSULTA_AGENDADA);
        h.setOcorridoEm(LocalDateTime.of(a.getDataAgendamento(), a.getHoraAtendimento()));
        h.registrarOrigemDoAgendamento(a);
        return historicos.saveAndFlush(h);
    }

    private HistoricoPaciente eventoManual(Paciente p, Usuario executor, int dia) {
        HistoricoPaciente h = new HistoricoPaciente();
        h.setPaciente(p);
        h.setProfissional(executor);
        h.setTipo(TipoEventoHistoricoPaciente.BUSCA_ATIVA);
        h.setOcorridoEm(LocalDateTime.of(2026, 12, dia, 11, 0));
        h.setDescricao("Acao sintetica na propria unidade");
        h.registrarOrigemDeAcaoLocal(executor);
        return historicos.saveAndFlush(h);
    }

    private SessaoGrupo sessaoComParticipacao(Paciente p, Usuario coordenador) {
        GrupoTerapeutico g = new GrupoTerapeutico();
        g.setTema("Grupo sintetico local");
        g.setCoordenador(coordenador);
        g.registrarUnidadeDeOrigem(coordenador);
        g = grupos.saveAndFlush(g);

        SessaoGrupo s = new SessaoGrupo();
        s.setGrupo(g);
        s.setDataSessao(LocalDate.of(2026, 12, 24));
        s.setHorario(LocalTime.of(15, 0));
        s.setStatus(StatusSessaoGrupo.AGENDADA);
        s.registrarUnidadeDeOrigemDoGrupo();
        s = sessoes.saveAndFlush(s);

        SessaoGrupoParticipante participacao = new SessaoGrupoParticipante();
        participacao.setPaciente(p);
        participacao.setSessaoGrupo(s);
        participantes.saveAndFlush(participacao);
        return s;
    }

    @Test
    void linhaDoTempoMantemCronologiaPorUnidadeEDetectaFatoAntigoSemOrigem() {
        Paciente pessoa = pessoa();
        UsfReferencia usf = usf("USF cronologia");
        Usuario uUsf = profissional(UnidadeAtuacao.USF, usf);
        Usuario uCaps = profissional(UnidadeAtuacao.CAPS_AD, null);
        vinculos.saveAndFlush(VinculoAssistencial.paraUsf(pessoa, usf));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(pessoa, UnidadeAtuacao.CAPS_AD));

        Agendamento faltaUsf = agenda(pessoa, uUsf, 17, 8);
        faltaUsf.setSituacaoAtendimento(SituacaoAtendimento.FALTOU);
        agendamentos.saveAndFlush(faltaUsf);
        Agendamento presencaUsf = agenda(pessoa, uUsf, 19, 8);
        presencaUsf.setSituacaoAtendimento(SituacaoAtendimento.PRESENTE);
        agendamentos.saveAndFlush(presencaUsf);
        Agendamento faltaCaps = agenda(pessoa, uCaps, 18, 8);
        faltaCaps.setSituacaoAtendimento(SituacaoAtendimento.FALTOU);
        agendamentos.saveAndFlush(faltaCaps);

        Agendamento legado = new Agendamento();
        legado.setPaciente(pessoa);
        legado.setUsuario(uUsf);
        legado.setDataAgendamento(LocalDate.of(2026, 12, 16));
        legado.setHoraAtendimento(LocalTime.of(9, 0));
        legado.setSituacaoAtendimento(SituacaoAtendimento.FALTOU);
        agendamentos.saveAndFlush(legado);

        atuarComo(uUsf);
        var usfLinha = linhaDoTempo.analisar(pessoa.getIdPublico());
        assertEquals(2, usfLinha.eventosComOrigemLocal().size());
        assertEquals(faltaUsf.getId(), usfLinha.eventosComOrigemLocal().get(0).registroId());
        assertFalse(usfLinha.eventosComOrigemLocal().get(0).presente());
        assertEquals(presencaUsf.getId(), usfLinha.eventosComOrigemLocal().get(1).registroId());
        assertTrue(usfLinha.eventosComOrigemLocal().get(1).presente());
        assertEquals(1, usfLinha.agendamentosSemOrigemComFrequencia());
        assertTrue(usfLinha.haEvidenciaHistoricaSemUnidadeComprovada());

        atuarComo(uCaps);
        var capsLinha = linhaDoTempo.analisar(pessoa.getIdPublico());
        assertEquals(1, capsLinha.eventosComOrigemLocal().size());
        assertEquals(faltaCaps.getId(), capsLinha.eventosComOrigemLocal().getFirst().registroId());
        assertEquals(1, capsLinha.agendamentosSemOrigemComFrequencia());
    }

    @Test
    void linhaDoTempoIncluiFrequenciaGrupoDaMesmaUnidadeSemConfundirCaps() {
        Paciente pessoa = pessoa();
        UsfReferencia usf = usf("USF grupos cronologicos");
        Usuario uUsf = profissional(UnidadeAtuacao.USF, usf);
        Usuario uCaps = profissional(UnidadeAtuacao.CAPS_AD, null);
        vinculos.saveAndFlush(VinculoAssistencial.paraUsf(pessoa, usf));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(pessoa, UnidadeAtuacao.CAPS_AD));

        Agendamento faltaUsf = agenda(pessoa, uUsf, 20, 10);
        faltaUsf.setSituacaoAtendimento(SituacaoAtendimento.FALTOU);
        agendamentos.saveAndFlush(faltaUsf);

        SessaoGrupo sessao = sessaoComParticipacao(pessoa, uUsf);
        sessao.confirmarRealizacao(uUsf);
        sessoes.saveAndFlush(sessao);
        var participacao = participantes.findBySessaoGrupoAndPaciente(sessao, pessoa).orElseThrow();
        participacao.setStatusPresenca(com.pet.buscaativa.entities.enums.StatusPresencaGrupo.PRESENTE);
        participantes.saveAndFlush(participacao);

        atuarComo(uUsf);
        var linha = linhaDoTempo.analisar(pessoa.getIdPublico());
        assertEquals(2, linha.eventosComOrigemLocal().size());
        assertEquals(LinhaDoTempoFrequenciasVinculoService.Fonte.ATENDIMENTO_INDIVIDUAL,
                linha.eventosComOrigemLocal().get(0).fonte());
        assertEquals(LinhaDoTempoFrequenciasVinculoService.Fonte.SESSAO_GRUPO,
                linha.eventosComOrigemLocal().get(1).fonte());
        assertTrue(linha.eventosComOrigemLocal().get(1).presente());
        assertFalse(linha.haEvidenciaHistoricaSemUnidadeComprovada());

        atuarComo(uCaps);
        assertTrue(linhaDoTempo.analisar(pessoa.getIdPublico()).eventosComOrigemLocal().isEmpty());
    }

    @Test
    void frequenciasComprovadasSaoInventariadasSeparadamenteSemMudarRiscoGlobal() {
        Paciente p = pessoa();
        UsfReferencia usfA = usf("USF inventario A");
        Usuario ua = profissional(UnidadeAtuacao.USF, usfA);
        Usuario caps = profissional(UnidadeAtuacao.CAPS_AD, null);
        vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, usfA));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));

        Agendamento presenteUsf = agenda(p, ua, 20, 9);
        presenteUsf.setSituacaoAtendimento(SituacaoAtendimento.PRESENTE);
        agendamentos.saveAndFlush(presenteUsf);
        Agendamento faltaCaps = agenda(p, caps, 21, 10);
        faltaCaps.setSituacaoAtendimento(SituacaoAtendimento.FALTOU);
        agendamentos.saveAndFlush(faltaCaps);

        int faltasGlobaisAntes = p.getCountFaltas();
        var riscoAntes = p.getClassificacaoRisco();
        atuarComo(ua);
        var a = inventario.inventariar(p.getIdPublico());
        assertEquals(1, a.presencasIndividuais());
        assertEquals(0, a.faltasIndividuais());
        assertEquals(0, a.presencasEmGrupo());
        assertEquals(0, a.faltasEmGrupo());
        assertEquals(0, a.agendamentosLocaisPendentesDeEvidencia());

        atuarComo(caps);
        var b = inventario.inventariar(p.getIdPublico());
        assertEquals(0, b.presencasIndividuais());
        assertEquals(1, b.faltasIndividuais());
        assertEquals(0, b.agendamentosLocaisPendentesDeEvidencia());

        assertEquals(faltasGlobaisAntes, p.getCountFaltas());
        assertEquals(riscoAntes, p.getClassificacaoRisco());
        assertEquals(1, pacientes.count());
    }

    @Test
    void inventarioRecusaUnidadeSemVinculoMesmoComReferenciaInformada() {
        Paciente p = pessoa();
        Usuario caps = profissional(UnidadeAtuacao.CAPS_AD, null);
        agenda(p, caps, 20, 9);
        atuarComo(caps);
        assertThrows(ResourceNotFoundException.class, () -> inventario.inventariar(p.getIdPublico()));
    }

    @Test
    void usfECapsCompartilhamSomenteIdentidadeNaoAtendimentosNemHistoricos() {
        Paciente p = pessoa();
        UsfReferencia usf = usf("USF A");
        Usuario uUsf = profissional(UnidadeAtuacao.USF, usf);
        Usuario uCaps = profissional(UnidadeAtuacao.CAPS_AD, null);

        vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, usf));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        var consultaUsf = agenda(p, uUsf, 20, 9);
        var consultaCaps = agenda(p, uCaps, 21, 9);
        var hUsf = eventoDeAgenda(consultaUsf);
        var hCaps = eventoDeAgenda(consultaCaps);
        var hManualUsf = eventoManual(p, uUsf, 22);
        var grupoCaps = sessaoComParticipacao(p, uCaps);

        atuarComo(uUsf);
        var dadosUsf = leitura.consultarIdentificadoresLocais(p.getIdPublico());
        assertEquals(java.util.List.of(consultaUsf.getId()), dadosUsf.agendamentos());
        assertTrue(dadosUsf.historicos().containsAll(java.util.List.of(hUsf.getId(), hManualUsf.getId())));
        assertEquals(2, dadosUsf.historicos().size());
        assertFalse(dadosUsf.historicos().contains(hCaps.getId()));
        assertTrue(dadosUsf.sessoesComParticipacao().isEmpty());

        atuarComo(uCaps);
        var dadosCaps = leitura.consultarIdentificadoresLocais(p.getIdPublico());
        assertEquals(java.util.List.of(consultaCaps.getId()), dadosCaps.agendamentos());
        assertEquals(java.util.List.of(hCaps.getId()), dadosCaps.historicos());
        assertEquals(java.util.List.of(grupoCaps.getId()), dadosCaps.sessoesComParticipacao());
        assertFalse(dadosCaps.historicos().contains(hManualUsf.getId()));

        assertEquals(1, pacientes.count());
        assertEquals(2, vinculos.findByPacienteId(p.getId()).size());
    }

    @Test
    void segundaUsfSemVinculoNaoLeHistoricoDaPrimeiraNemPorReferencia() {
        Paciente p = pessoa();
        UsfReferencia usfA = usf("USF A");
        UsfReferencia usfB = usf("USF B");
        // A referencia territorial não prova vínculo e não altera filtros.
        p.setUsfReferencia(usfB);
        pacientes.saveAndFlush(p);

        Usuario ua = profissional(UnidadeAtuacao.USF, usfA);
        Usuario ub = profissional(UnidadeAtuacao.USF, usfB);
        vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, usfA));
        var agendaA = agenda(p, ua, 20, 10);
        eventoDeAgenda(agendaA);

        atuarComo(ua);
        assertEquals(java.util.List.of(agendaA.getId()), leitura.consultarIdentificadoresLocais(p.getIdPublico()).agendamentos());

        atuarComo(ub);
        assertThrows(ResourceNotFoundException.class,
                () -> leitura.consultarIdentificadoresLocais(p.getIdPublico()));

        vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, usfB));
        var b = leitura.consultarIdentificadoresLocais(p.getIdPublico());
        assertTrue(b.agendamentos().isEmpty());
        assertTrue(b.historicos().isEmpty());
        assertTrue(b.sessoesComParticipacao().isEmpty());
    }
}
