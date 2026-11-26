package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.junit.jupiter.api.Test;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;

/** Observações sobre dados legados sintéticos e proteção de escopo. */
class RemainingAmbiguity61Characterization extends Historical61Support {
    @Test void overdueSessionTransferRecordsWhetherNewOwnerIsInferred() throws Exception {
        var g=session61(professional,PAST,SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        var version=groups61.findById(g.group()).orElseThrow().getVersion();
        var dto=new AtualizarGrupoTerapeuticoDTO("Grupo histórico sintético 6.1",colleague.getIdPublico(),PAST,SLOT,RecorrenciaGrupo.UNICA,null,version);
        allow61("RESP-09-TRANSFER",()->http61(put(GROUP+"/"+g.group()),dto));
        // Nenhuma evidência informa quem efetivamente realizou o atendimento no passado.
        var r=confirm61(g,patient);int status=businessStatus(r);
        var current=sessions61.findById(g.session()).orElseThrow();
        Long owner=jdbc.queryForObject("select responsavel_atendimento_id from tb_sessao_grupo where id=?",Long.class,g.session());
        System.out.printf("E61 RESP-09 OBSERVACAO status=%d session=%s originalCoordinator=%d currentCoordinator=%d assigned=%s pending=%s%n",status,current.getStatus(),professional.getId(),colleague.getId(),owner,current.isResponsabilidadePendente());
        assertTrue(status==200 || status==409 || status==422,r.getContentAsString());
        if(status==200)assertEquals(StatusSessaoGrupo.REALIZADA,current.getStatus());
        else assertEquals(StatusSessaoGrupo.AGENDADA,current.getStatus());
    }
    @Test void foreignAdministratorCannotProbeHistoricalCollision() throws Exception {
        var a=individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.PRESENTE,patient);
        var foreign=actor("foreign");var before=database();
        var response=mvc.perform(patch("/api/agendamentos/"+a.getId()+"/frequencia/correcao")
            .header("Authorization","Bearer "+tokens.gerarToken(foreign)).contentType("application/json")
            .content(json.writeValueAsString(new CorrigirFrequenciaDTO(FrequenciaAssistencial.PRESENTE,a.getVersion(),REASON)))).andReturn().getResponse();
        int status=businessStatus(response);boolean unchanged=before.equals(database());String body=response.getContentAsString();
        System.out.printf("E61 K-07 expected=404 actual=%d unchanged=%s%n",status,unchanged);
        assertAll(()->assertEquals(404,status),()->assertTrue(unchanged),()->assertFalse(body.contains(patient.getNome())),()->assertFalse(body.contains(patient.getIdPublico().toString())),()->assertFalse(body.contains("tipoConflito")));
    }
}
