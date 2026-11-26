package com.pet.buscaativa.services.impl;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.PlanoResolucaoFrequencia.Fato;
import com.pet.buscaativa.services.impl.PlanoResolucaoFrequencia.Referencia;
import static com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService.Fonte.*;

class PlanoResolucaoFrequenciaTests {
    final LocalDateTime hora=LocalDateTime.of(2026,11,10,9,0);
    final Referencia individual=new Referencia(ATENDIMENTO_INDIVIDUAL,1L,3);
    final Referencia grupo=new Referencia(SESSAO_GRUPO,1L,5);
    final Fato a=new Fato(individual,10L,hora,true);
    final Fato b=new Fato(grupo,10L,hora,false);
    @Test void profissionalDosDoisPodeResolverComIdentidadesDeFontesDistintas() {
        assertDoesNotThrow(()->PlanoResolucaoFrequencia.validar(individual,grupo,a,b,10L,TipoUsuario.PROFISSIONAL));
    }
    @Test void administracaoPodeResolverResponsaveisDiferentesMasProfissionalNao() {
        var outro=new Fato(grupo,11L,hora,false);
        assertDoesNotThrow(()->PlanoResolucaoFrequencia.validar(individual,grupo,a,outro,12L,TipoUsuario.ADMINISTRADOR));
        assertThrows(AccessDeniedException.class,()->PlanoResolucaoFrequencia.validar(individual,grupo,a,outro,10L,TipoUsuario.PROFISSIONAL));
        assertThrows(AccessDeniedException.class,()->PlanoResolucaoFrequencia.validar(individual,grupo,a,b,10L,TipoUsuario.RECEPCAO));
    }
    @Test void versaoObsoletaEHorariosDiferentesSaoRecusados() {
        var antiga=new Referencia(ATENDIMENTO_INDIVIDUAL,1L,2);
        assertThrows(ConflictException.class,()->PlanoResolucaoFrequencia.validar(antiga,grupo,a,b,10L,TipoUsuario.PROFISSIONAL));
        var outro=new Fato(grupo,10L,hora.plusHours(1),false);
        assertThrows(ConflictException.class,()->PlanoResolucaoFrequencia.validar(individual,grupo,a,outro,10L,TipoUsuario.PROFISSIONAL));
    }
    @Test void mesmoRegistroNaoPodeSerValidoEDesconsiderado() {
        assertThrows(ValidationException.class,()->PlanoResolucaoFrequencia.validar(individual,individual,a,a,10L,TipoUsuario.PROFISSIONAL));
    }
}
