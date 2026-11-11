package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.config.AgendaHorarioProperties;

class HistoricalCorrectionOfferTests extends FrequencyCorrectionTestSupport {
    @Autowired AgendaHorarioProperties grid;
    @ParameterizedTest @CsvSource({"no_offer,FALTOU","zero,FALTOU","reduced,FALTOU","full,FALTOU","blocked,FALTOU","shift,FALTOU","grid,FALTOU","no_offer,PRESENTE","zero,PRESENTE","reduced,PRESENTE","full,PRESENTE","blocked,PRESENTE","shift,PRESENTE","grid,PRESENTE"})
    void ag01To05BothDirectionsIgnoreChangedOfferButPreserveIdentity(String offer,SituacaoAtendimento initial) throws Exception {
        var day=LocalDate.now().minusDays(10);var a=fact(initial,day,"08:00");int interval=grid.getIntervaloMinutos();
        try{tx(()->{switch(offer){case "no_offer"->{}case "zero"->capacity(professional,day,TurnoEnum.MANHA,0);case "reduced"->capacity(professional,day,TurnoEnum.MANHA,1);case "full"->{capacity(professional,day,TurnoEnum.MANHA,1);seed(other,professional,SituacaoAtendimento.PRESENTE,day,"09:00");}case "blocked"->{var b=new BloqueioAgenda();b.setUsuario(professional);b.setDataInicio(day);b.setDataFim(day);b.setMotivoBloqueio("Bloqueio sintético cadastrado depois do atendimento.");blocks.saveAndFlush(b);}case "shift"->capacity(professional,day,TurnoEnum.TARDE,10);case "grid"->grid.setIntervaloMinutos(45);default->throw new IllegalArgumentException();}return null;});
            long rows=appointments.count(),events=history.count(),count=audits.count();var next=initial==SituacaoAtendimento.FALTOU?FrequenciaAssistencial.PRESENTE:FrequenciaAssistencial.FALTOU;assertEquals(200,businessStatus(correct(professional,a,next,a.getVersion())));var saved=appointment(a.getId());assertEquals(a.getId(),saved.getId());assertEquals(day,saved.getDataAgendamento());assertEquals(LocalTime.of(8,0),saved.getHoraAtendimento());assertEquals(TurnoEnum.MANHA,saved.getTurnoAgendamento());assertEquals(next,saved.getFrequenciaAssistencial());assertEquals(rows,appointments.count());assertEquals(events,history.count());assertEquals(count+1,audits.count());
        }finally{grid.setIntervaloMinutos(interval);}
    }
}
