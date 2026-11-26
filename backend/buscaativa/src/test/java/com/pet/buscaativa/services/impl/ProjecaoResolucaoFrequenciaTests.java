package com.pet.buscaativa.services.impl;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import com.pet.buscaativa.services.impl.ProjecaoResolucaoFrequencia.*;
import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService.Evento;
import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService.Diagnostico;
import static com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService.Fonte.*;

class ProjecaoResolucaoFrequenciaTests {
    final LocalDateTime hora=LocalDateTime.of(2026,11,10,9,0);
    final Fato individual=new Fato(new Evento(hora,false,ATENDIMENTO_INDIVIDUAL,1L),10L);
    final Fato grupo=new Fato(new Evento(hora,false,SESSAO_GRUPO,1L),10L);
    final Fato terceiro=new Fato(new Evento(hora.plusHours(2),false,ATENDIMENTO_INDIVIDUAL,2L),10L);
    Resolucao resolucao(Fato a,Fato b) {
        return new Resolucao(a.chave(),b.chave(),a.evento().ocorridoEm(),a.evento().presente(),
                b.evento().presente(),a.responsavel(),b.responsavel());
    }
    OptionalInt contar(List<Fato> fatos,List<Resolucao> resolucoes) {
        var r=ProjecaoResolucaoFrequencia.aplicar(fatos,resolucoes);
        var eventos=fatos.stream().filter(f->!r.desconsiderados().contains(f.chave())).map(Fato::evento).toList();
        return DiagnosticoFaltasConsecutivasVinculoService.calcular(
                new Diagnostico(eventos,0,0,r.inconclusivo()?1:0,0)).faltasConsecutivasAnalisaveis();
    }
    @Test void duplicidadeResolvidaExcluiSomenteFonteSelecionadaEReversaoRestauraContagem() {
        var fatos=List.of(individual,grupo,terceiro);
        assertEquals(OptionalInt.of(3),contar(fatos,List.of()));
        assertEquals(OptionalInt.of(2),contar(fatos,List.of(resolucao(individual,grupo))));
        assertEquals(OptionalInt.of(3),contar(fatos,List.of()));
        assertEquals(3,fatos.size());
    }
    @Test void contradicaoResolvidaPreservaPresencaEReiniciaContagem() {
        var presenca=new Fato(new Evento(hora,true,SESSAO_GRUPO,1L),10L);
        var fatos=List.of(individual,presenca,terceiro);
        assertTrue(contar(fatos,List.of()).isEmpty());
        assertEquals(OptionalInt.of(1),contar(fatos,List.of(resolucao(presenca,individual))));
    }
    @Test void resolucaoObsoletaNaoSuprimeFatoAlteradoOuAusente() {
        var r=resolucao(individual,grupo);
        var alterado=new Fato(new Evento(hora,true,SESSAO_GRUPO,1L),10L);
        for(var fatos:List.of(List.of(individual,alterado),List.of(individual))) {
            var resultado=ProjecaoResolucaoFrequencia.aplicar(fatos,List.of(r));
            assertTrue(resultado.inconclusivo());
            assertTrue(resultado.desconsiderados().isEmpty());
        }
    }
    @Test void recusaCicloESupressaoRepetida() {
        var r=resolucao(individual,grupo);
        var volta=resolucao(grupo,individual);
        for(var resolucoes:List.of(List.of(r,r),List.of(r,volta))) {
            var resultado=ProjecaoResolucaoFrequencia.aplicar(List.of(individual,grupo),resolucoes);
            assertTrue(resultado.inconclusivo());
            assertTrue(resultado.desconsiderados().isEmpty());
        }
    }
}
