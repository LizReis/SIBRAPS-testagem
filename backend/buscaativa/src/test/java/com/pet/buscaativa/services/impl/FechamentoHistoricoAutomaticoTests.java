package com.pet.buscaativa.services.impl;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.pet.buscaativa.migration.ObservacaoCoberturaHistorica;

class FechamentoHistoricoAutomaticoTests {
    @Test
    void janelaExigeObservacoesNosDoisLadosDoCorte() {
        var zona=ZoneId.of("America/Bahia");
        var corte=Instant.parse("2027-02-01T03:00:00Z");
        assertNull(FechamentoHistoricoAutomaticoService.janela(corte.minusSeconds(61),zona));
        var antes=FechamentoHistoricoAutomaticoService.janela(corte.minusSeconds(60),zona);
        var depois=FechamentoHistoricoAutomaticoService.janela(corte,zona);
        assertTrue(antes.antes());
        assertFalse(depois.antes());
        assertEquals(YearMonth.of(2027,1),antes.mes());
        assertEquals(antes.mes(),depois.mes());
        assertNull(FechamentoHistoricoAutomaticoService.janela(corte.plusSeconds(300),zona));
        var pre=new FechamentoHistoricoAutomaticoService.Pre(corte.minusSeconds(30),"a","[1]");
        var prova=new ObservacaoCoberturaHistorica.Local(true,"a",List.of(1L));
        assertTrue(FechamentoHistoricoAutomaticoService.confere(pre,depois,prova,"[1]"));
        assertFalse(FechamentoHistoricoAutomaticoService.confere(pre,depois,new ObservacaoCoberturaHistorica.Local(true,"b",List.of(1L)),"[1]"));
        assertFalse(FechamentoHistoricoAutomaticoService.confere(pre,depois,prova,"[2]"));
        assertFalse(FechamentoHistoricoAutomaticoService.confere(pre,depois,new ObservacaoCoberturaHistorica.Local(false,"a",List.of(1L)),"[1]"));
        assertFalse(FechamentoHistoricoAutomaticoService.confere(new FechamentoHistoricoAutomaticoService.Pre(corte,"a","[1]"),depois,prova,"[1]"));
    }
}
