package com.pet.buscaativa.services.impl;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService.Evento;
import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService.Fonte;

/** Projeção derivada, sem alterar fatos nem interpretar coincidência como duplicidade. */
final class ProjecaoResolucaoFrequencia {
    private ProjecaoResolucaoFrequencia() {}
    record Chave(Fonte fonte, Long id) {}
    record Fato(Evento evento, Long responsavel) {
        Chave chave() {return new Chave(evento.fonte(),evento.registroId());}
    }
    record Resolucao(Chave valido, Chave desconsiderado, LocalDateTime horario,
            boolean presencaValida, boolean presencaDesconsiderada,
            Long responsavelValido, Long responsavelDesconsiderado) {}
    record Resultado(Set<Chave> desconsiderados, boolean inconclusivo) {}

    static Resultado aplicar(List<Fato> fatos,List<Resolucao> resolucoes) {
        if(resolucoes.isEmpty()) return new Resultado(Set.of(),false);
        Map<Chave,Fato> porChave=new HashMap<>();
        for(var fato:fatos) if(porChave.put(fato.chave(),fato)!=null) return inconclusivo();
        Set<Chave> excluidos=new HashSet<>();
        Set<Chave> validos=new HashSet<>();
        for(var r:resolucoes) {
            var a=porChave.get(r.valido());
            var b=porChave.get(r.desconsiderado());
            if(r.valido().equals(r.desconsiderado()) || !confere(a,r.horario(),r.presencaValida(),r.responsavelValido())
                    || !confere(b,r.horario(),r.presencaDesconsiderada(),r.responsavelDesconsiderado())
                    || !excluidos.add(r.desconsiderado())) return inconclusivo();
            validos.add(r.valido());
        }
        // Uma supressão não pode ser a base de outra; ciclos e cadeias
        // inconsistentes jamais produzem uma contagem conclusiva.
        if(validos.stream().anyMatch(excluidos::contains)) return inconclusivo();
        return new Resultado(Set.copyOf(excluidos),false);
    }

    private static boolean confere(Fato f,LocalDateTime hora,boolean presente,Long responsavel) {
        return f!=null && hora!=null && hora.equals(f.evento().ocorridoEm())
                && presente==f.evento().presente() && responsavel!=null && responsavel.equals(f.responsavel());
    }
    private static Resultado inconclusivo() {return new Resultado(Set.of(),true);}
}
