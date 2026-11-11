package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.*;
import tools.jackson.databind.JsonNode;

class GroupHistoricalResponsibilityTests extends AgendaIntegritySupport {
    static final String REASON="Retificação após conferência do prontuário sintético da sessão.";
    @Autowired GrupoTerapeuticoRepository groups;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean HistoricoPacienteService histories;
    @Autowired SessaoGrupoRepository sessions;
    @Autowired SessaoGrupoParticipanteRepository participants;
    @Autowired AuditoriaCorrecaoRepository audits;
    @Autowired GrupoTerapeuticoService groupService;
    @Autowired UsfReferenciaRepository usfs;
    private SessaoGrupo scheduled(){return tx(()->{
        var g=new GrupoTerapeutico();g.setCoordenador(professional);g.setTema("Grupo sintético histórico");g.setRecorrencia(RecorrenciaGrupo.UNICA);g.setHorarioPadrao(LocalTime.of(8,0));g.setAtivo(true);groups.saveAndFlush(g);
        var s=new SessaoGrupo();s.setGrupo(g);s.setStatus(StatusSessaoGrupo.AGENDADA);s.setDataSessao(LocalDate.now().minusDays(5));s.setHorario(LocalTime.of(8,0));sessions.saveAndFlush(s);
        var p=new SessaoGrupoParticipante();p.setSessaoGrupo(s);p.setPaciente(patient);p.setStatusPresenca(StatusPresencaGrupo.NAO_REGISTRADA);participants.saveAndFlush(p);return s;
    });}
    private int confirm(Usuario actor,SessaoGrupo s) throws Exception {
        var dto=new ConfirmarOcorrenciaGrupoDTO(true,List.of(new FrequenciaParticipanteGrupoDTO(patient.getIdPublico(),StatusPresencaGrupo.FALTOU)),null,s.getVersion());
        return businessStatus(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/grupos/sessoes/"+s.getId()+"/confirmacao-ocorrencia").header("Authorization","Bearer "+tokens.gerarToken(actor)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse());
    }
    private SessaoGrupo performed() throws Exception {var s=scheduled();assertEquals(200,confirm(professional,s));return sessions.findById(s.getId()).orElseThrow();}
    private AtualizarGrupoTerapeuticoDTO transferDTO(SessaoGrupo s){return tx(()->{var g=groups.findById(s.getGrupo().getId()).orElseThrow();var first=sessions.findByGrupoOrderByDataSessaoDesc(g).stream().min(Comparator.comparing(SessaoGrupo::getDataSessao)).orElseThrow();return new AtualizarGrupoTerapeuticoDTO(g.getTema(),colleague.getIdPublico(),first.getDataSessao(),g.getHorarioPadrao(),g.getRecorrencia(),g.getDataFimRecorrencia(),g.getVersion());});}
    private int transfer(SessaoGrupo s,AtualizarGrupoTerapeuticoDTO dto) throws Exception {return businessStatus(mvc.perform(put("/api/grupos/"+s.getGrupo().getId()).header("Authorization","Bearer "+tokens.gerarToken(admin)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse());}
    private void transfer(SessaoGrupo s) throws Exception {assertEquals(200,transfer(s,transferDTO(s)));}
    private int correction(Usuario actor,SessaoGrupo s,String reason) throws Exception {
        var dto=new CorrigirFrequenciasGrupoDTO(List.of(new FrequenciaParticipanteGrupoDTO(patient.getIdPublico(),StatusPresencaGrupo.PRESENTE)),s.getVersion(),reason);
        return businessStatus(mvc.perform(patch("/api/grupos/sessoes/"+s.getId()+"/frequencias").header("Authorization","Bearer "+tokens.gerarToken(actor)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse());
    }
    private Object database(){var ds=Objects.requireNonNull(jdbc.getDataSource());return LegacyMigrationFixture.snapshot(ds,LegacyMigrationFixture.columns(ds));}
    private JsonNode records(Usuario actor,SessaoGrupo s,int expected) throws Exception {var r=mvc.perform(get("/api/grupos/sessoes/"+s.getId()+"/correcoes").header("Authorization","Bearer "+tokens.gerarToken(actor))).andReturn().getResponse();assertEquals(expected,r.getStatus(),r.getContentAsString());return expected==200?json.readTree(r.getContentAsString()).get("content"):null;}
    private void owner(SessaoGrupo s,Usuario expected){assertEquals(expected.getId(),jdbc.queryForObject("select responsavel_atendimento_id from tb_sessao_grupo where id=?",Long.class,s.getId()));assertFalse(jdbc.queryForObject("select responsabilidade_pendente from tb_sessao_grupo where id=?",Boolean.class,s.getId()));}
    @Test void gr01FormerCoordinatorCanCorrectWithReasonAndImmutableOwner() throws Exception {var s=performed();transfer(s);assertEquals(422,correction(professional,s,null));assertEquals(200,correction(professional,s,REASON));owner(s,professional);var row=records(professional,s,200).get(0);assertEquals(professional.getIdPublico().toString(),row.get("usuarioId").asText());assertEquals(professional.getIdPublico().toString(),row.get("responsavelAtendimentoId").asText());}
    @Test void gr02NewCoordinatorCannotCorrectOldSessionOrReadClinicalAudit() throws Exception {var s=performed();transfer(s);var before=database();assertEquals(403,correction(colleague,s,REASON));assertEquals(before,database());records(colleague,s,403);}
    private SessaoGrupo nextSession(SessaoGrupo old){return tx(()->{var s=new SessaoGrupo();s.setGrupo(groups.findById(old.getGrupo().getId()).orElseThrow());s.setStatus(StatusSessaoGrupo.AGENDADA);s.setDataSessao(LocalDate.now().minusDays(2));s.setHorario(LocalTime.of(8,0));sessions.saveAndFlush(s);var p=new SessaoGrupoParticipante();p.setSessaoGrupo(s);p.setPaciente(patient);p.setStatusPresenca(StatusPresencaGrupo.NAO_REGISTRADA);participants.saveAndFlush(p);return s;});}
    @Test void gr03And04NewCoordinatorOwnsAndCorrectsNewSession() throws Exception {var old=performed();transfer(old);var newer=nextSession(old);assertEquals(200,confirm(colleague,newer));owner(old,professional);owner(newer,colleague);newer=sessions.findById(newer.getId()).orElseThrow();assertEquals(200,correction(colleague,newer,REASON));owner(newer,colleague);var row=records(colleague,newer,200).get(0);assertEquals(colleague.getIdPublico().toString(),row.get("responsavelAtendimentoId").asText());}
    @Test void gr05FormerCoordinatorCannotCorrectSuccessorsSession() throws Exception {var old=performed();transfer(old);var newer=nextSession(old);assertEquals(200,confirm(colleague,newer));newer=sessions.findById(newer.getId()).orElseThrow();var before=database();assertEquals(403,correction(professional,newer,REASON));assertEquals(before,database());}
    @Test void gr06AdminActorDoesNotReplaceHistoricalResponsible() throws Exception {var s=performed();transfer(s);assertEquals(200,correction(admin,s,REASON));owner(s,professional);var row=records(admin,s,200).get(0);assertEquals(admin.getIdPublico().toString(),row.get("usuarioId").asText());assertEquals(professional.getIdPublico().toString(),row.get("responsavelAtendimentoId").asText());}
    @Test void gr07ExternalAdminCannotReadOrCorrect() throws Exception {var s=performed();transfer(s);var external=tx(()->user(TipoUsuario.ADMINISTRADOR,UnidadeAtuacao.CAPS_I));var before=database();assertEquals(404,correction(external,s,REASON));records(external,s,404);assertEquals(before,database());}
    @Test void gr08DifferentUsfCannotCorrectEvenAsAdmin() throws Exception {
        tx(()->{var x=new UsfReferencia();x.setCnes(UUID.randomUUID().toString());usfs.saveAndFlush(x);var y=new UsfReferencia();y.setCnes(UUID.randomUUID().toString());usfs.saveAndFlush(y);for(var u:List.of(admin,professional,colleague)){u.setUnidadeAtuacao(UnidadeAtuacao.USF);u.setUsfAtuacao(x);users.saveAndFlush(u);}var p=patients.findById(patient.getId()).orElseThrow();p.setCapsReferencia(null);p.setUsfReferencia(x);patients.saveAndFlush(p);return null;});
        var s=performed();transfer(s);var external=tx(()->{var x=new UsfReferencia();x.setCnes(UUID.randomUUID().toString());usfs.saveAndFlush(x);var u=user(TipoUsuario.ADMINISTRADOR,UnidadeAtuacao.USF);u.setUsfAtuacao(x);return users.saveAndFlush(u);});var before=database();assertEquals(404,correction(external,s,REASON));records(external,s,404);assertEquals(before,database());
    }
    @Test void gr09UnperformedSessionFollowsTransferredCoordinator() throws Exception {var s=scheduled();transfer(s);var before=database();assertEquals(403,confirm(professional,s));assertEquals(before,database());assertEquals(200,confirm(colleague,s));owner(s,colleague);}
    @Test void gr10AmbiguousLegacyIsProtectedWithoutInventedOwner() throws Exception {var s=scheduled();tx(()->{var x=sessions.findById(s.getId()).orElseThrow();x.setStatus(StatusSessaoGrupo.REALIZADA);sessions.saveAndFlush(x);return null;});var before=database();assertEquals(409,correction(admin,s,REASON));assertEquals(409,correction(professional,s,REASON));records(professional,s,403);records(admin,s,200);assertEquals(before,database());assertNull(jdbc.queryForObject("select responsavel_atendimento_id from tb_sessao_grupo where id=?",Long.class,s.getId()));}
    @Test void gr12TransferDoesNotRewritePreviousAuditsAndOldOwnerCanReadThem() throws Exception {var s=performed();assertEquals(200,correction(admin,s,REASON));var before=jdbc.queryForList("select * from tb_auditoria_correcao_frequencia order by id");transfer(s);assertEquals(before,jdbc.queryForList("select * from tb_auditoria_correcao_frequencia order by id"));owner(s,professional);assertEquals(1,records(professional,s,200).size());records(colleague,s,403);records(admin,s,200);}
    @Test void receptionCannotCorrectHistoricalSession() throws Exception {var s=performed();transfer(s);var reception=tx(()->user(TipoUsuario.RECEPCAO,UnidadeAtuacao.CAPS_AD));var before=database();assertEquals(403,correction(reception,s,REASON));records(reception,s,403);assertEquals(before,database());}
    @Test void retroactiveEnrollmentCannotLetNewCoordinatorWriteOldOwnersFrequency() throws Exception {var s=performed();transfer(s);var dto=new InscricaoRetroativaGrupoDTO(other.getIdPublico(),List.of(new FrequenciaSessaoRetroativaDTO(s.getId(),StatusPresencaGrupo.PRESENTE)));var before=database();var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/grupos/"+s.getGrupo().getId()+"/inscricoes-retroativas").header("Authorization","Bearer "+tokens.gerarToken(colleague)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse();assertEquals(403,r.getStatus());assertEquals(before,database());}
    @Test void adminCanSuperviseWhenOriginalResponsibleHasMovedUnit() throws Exception {var s=performed();transfer(s);tx(()->{var u=users.findById(professional.getId()).orElseThrow();u.setUnidadeAtuacao(UnidadeAtuacao.CAPS_I);users.saveAndFlush(u);return null;});assertEquals(200,correction(admin,s,REASON));owner(s,professional);assertEquals(404,correction(professional,sessions.findById(s.getId()).orElseThrow(),REASON));}
    @Test void provisionalFrequencyDoesNotFreezeResponsibilityBeforeRealization() throws Exception {
        var s=scheduled();var r=mvc.perform(patch("/api/grupos/sessoes/"+s.getId()+"/participantes/"+patient.getIdPublico()+"/presenca").header("Authorization","Bearer "+tokens.gerarToken(professional)).contentType("application/json").content("{\"statusPresenca\":\"PRESENTE\"}")).andReturn().getResponse();assertEquals(200,r.getStatus());
        assertNull(jdbc.queryForObject("select responsavel_atendimento_id from tb_sessao_grupo where id=?",Long.class,s.getId()));transfer(s);assertEquals(200,confirm(colleague,s));owner(s,colleague);
    }
    @Test void gr09FutureSessionFollowsNewCoordinatorWithoutPrematurelyFreezingOwner() throws Exception {
        var old=performed();var future=nextSession(old);tx(()->{var x=sessions.findById(future.getId()).orElseThrow();x.setDataSessao(LocalDate.now().plusDays(30));sessions.saveAndFlush(x);return null;});transfer(old);
        for(var actor:List.of(professional,colleague)){var r=mvc.perform(patch("/api/grupos/sessoes/"+future.getId()+"/status").param("novoStatus","AGENDADA").header("Authorization","Bearer "+tokens.gerarToken(actor))).andReturn().getResponse();assertEquals(actor==colleague?200:403,r.getStatus());}
        assertNull(jdbc.queryForObject("select responsavel_atendimento_id from tb_sessao_grupo where id=?",Long.class,future.getId()));owner(old,professional);
        assertEquals(422,confirm(colleague,sessions.findById(future.getId()).orElseThrow()));
    }
    @Test void clientCannotChooseHistoricalOwnerAndAdminIsOnlyTheConfirmationActor() throws Exception {
        var s=scheduled();var request=new HashMap<String,Object>();request.put("ocorreu",true);request.put("frequencias",List.of(new FrequenciaParticipanteGrupoDTO(patient.getIdPublico(),StatusPresencaGrupo.FALTOU)));request.put("version",s.getVersion());request.put("responsavelAtendimentoId",colleague.getIdPublico());
        var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/grupos/sessoes/"+s.getId()+"/confirmacao-ocorrencia").header("Authorization","Bearer "+tokens.gerarToken(admin)).contentType("application/json").content(json.writeValueAsString(request))).andReturn().getResponse();assertEquals(200,r.getStatus());owner(s,professional);
        assertThrows(IllegalStateException.class,()->tx(()->{sessions.findById(s.getId()).orElseThrow().confirmarRealizacao(colleague);return null;}));owner(s,professional);
    }
    @Test void failureAfterRealizationWriteRollsBackOwnerFrequencyIndicatorsHistoryAndEnvers() {
        var s=scheduled();var before=database();org.mockito.Mockito.doAnswer(i->{i.callRealMethod();assertEquals(professional.getId(),jdbc.queryForObject("select responsavel_atendimento_id from tb_sessao_grupo where id=?",Long.class,s.getId()));throw new IllegalStateException("synthetic failure after realization write");}).when(histories).registrarFrequenciaGrupo(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());authenticate();
        var dto=new ConfirmarOcorrenciaGrupoDTO(true,List.of(new FrequenciaParticipanteGrupoDTO(patient.getIdPublico(),StatusPresencaGrupo.PRESENTE)),null,s.getVersion());assertThrows(IllegalStateException.class,()->groupService.confirmarOcorrencia(s.getId(),dto));assertEquals(before,database());
    }
    private List<Integer> concurrent(List<Callable<Integer>> operations) throws Exception {
        var pool=Executors.newFixedThreadPool(operations.size());var ready=new CountDownLatch(operations.size());var start=new CountDownLatch(1);var connections=ConcurrentHashMap.<Integer>newKeySet();
        try {var futures=operations.stream().map(op->pool.submit(()->new TransactionTemplate(manager).execute(status->{connections.add(jdbc.queryForObject("select session_id()",Integer.class));ready.countDown();authenticate();try{assertTrue(start.await(10,TimeUnit.SECONDS));int result=op.call();if(result>=400)status.setRollbackOnly();return result;}catch(Exception e){throw new IllegalStateException(e);}finally{SecurityContextHolder.clearContext();}}))).toList();assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();var result=new ArrayList<Integer>();for(var f:futures)result.add(f.get(30,TimeUnit.SECONDS));assertEquals(operations.size(),connections.size());assertTrue(result.stream().allMatch(x->x==200||x==403||x==409),result.toString());System.out.println("RESPONSIBILITY_RACE connections="+connections.size()+" results="+result);return result;}finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @RepeatedTest(2) void gr11TransferAndOldOwnerCorrectionBothRemainConsistent() throws Exception {var s=performed();var dto=transferDTO(s);long count=audits.count();assertEquals(List.of(200,200),concurrent(List.of(()->transfer(s,dto),()->correction(professional,s,REASON))));owner(s,professional);assertEquals(count+1,audits.count());assertEquals(professional.getIdPublico().toString(),records(admin,s,200).get(0).get("responsavelAtendimentoId").asText());}
    @RepeatedTest(2) void gr11TransferAndConfirmationNeverAuthorizeFromStaleCoordinator() throws Exception {var s=scheduled();var dto=transferDTO(s);var results=concurrent(List.of(()->transfer(s,dto),()->confirm(professional,s)));assertEquals(200,results.get(0));var status=sessions.findById(s.getId()).orElseThrow().getStatus();if(results.get(1)==200){assertEquals(StatusSessaoGrupo.REALIZADA,status);owner(s,professional);}else{assertEquals(403,results.get(1));assertEquals(StatusSessaoGrupo.AGENDADA,status);assertEquals(200,confirm(colleague,s));owner(s,colleague);}}
    @RepeatedTest(2) void gr11AdminConfirmationFreezesCoordinatorUnderSameDatabaseLock() throws Exception {var s=scheduled();var dto=transferDTO(s);assertEquals(List.of(200,200),concurrent(List.of(()->transfer(s,dto),()->confirm(admin,s))));Long id=jdbc.queryForObject("select responsavel_atendimento_id from tb_sessao_grupo where id=?",Long.class,s.getId());assertTrue(Set.of(professional.getId(),colleague.getId()).contains(id));Long atRealization=jdbc.queryForObject("select g.coordenador_id from tb_grupo_terapeutico_aud g where g.id=? and g.rev <= (select min(rev) from tb_sessao_grupo_aud where id=? and status='REALIZADA') order by g.rev desc limit 1",Long.class,s.getGrupo().getId(),s.getId());assertEquals(atRealization,id);}
}
