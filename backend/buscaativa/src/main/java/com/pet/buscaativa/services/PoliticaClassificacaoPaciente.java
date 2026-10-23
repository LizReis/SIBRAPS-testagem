package com.pet.buscaativa.services;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;

@Component
public class PoliticaClassificacaoPaciente {
    public static final int DIAS_AMARELO_INDIVIDUAL=60, DIAS_VERMELHO_INDIVIDUAL=120;

    public static final int DIAS_AMARELO_GRUPO=15, DIAS_VERMELHO_GRUPO=30;
    
    public boolean semPresencaRecente(Paciente p, LocalDate hoje) {
        if (p.getDataUltimaPresenca() == null) return true;
        boolean grupo=p.getTipoAcompanhamento()==TipoAcompanhamento.GRUPO_TERAPEUTICO || p.getTipoAcompanhamento()==TipoAcompanhamento.AMBOS;
        int limite=grupo?DIAS_AMARELO_GRUPO:DIAS_AMARELO_INDIVIDUAL;
        return ChronoUnit.DAYS.between(p.getDataUltimaPresenca(), hoje)>limite;
    }
}