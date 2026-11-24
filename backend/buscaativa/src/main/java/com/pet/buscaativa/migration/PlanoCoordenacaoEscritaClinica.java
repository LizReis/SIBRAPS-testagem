package com.pet.buscaativa.migration;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Contrato de ordenação da descoberta de recursos antes de qualquer escrita.
 * Não adquire locks, não autoriza acesso, não executa comandos clínicos.
 * As listas vêm exclusivamente de descoberta interna no servidor. A igualdade
 * do conjunto é necessária, mas não substitui revalidação de origem, perfil,
 * titularidade, versão, relações, estados e fatos sob as travas reais.
 */
public record PlanoCoordenacaoEscritaClinica(
        List<UUID> pacientes, List<UUID> usuarios, List<UUID> vinculos,
        List<Long> grupos, List<Long> sessoes, List<Long> agendamentos) {

    public PlanoCoordenacaoEscritaClinica {
        pacientes = ordenar(pacientes);
        usuarios = ordenar(usuarios);
        vinculos = ordenar(vinculos);
        grupos = ordenarIds(grupos);
        sessoes = ordenarIds(sessoes);
        agendamentos = ordenarIds(agendamentos);
    }

    public record Descoberta<T>(PlanoCoordenacaoEscritaClinica plano, T relacoes) {
        public Descoberta {
            Objects.requireNonNull(plano);
            Objects.requireNonNull(relacoes);
        }
    }

    public enum Fase { PACIENTES, USUARIOS, VINCULOS, GRUPOS, SESSOES, AGENDAMENTOS }

    public record Recurso(Fase fase, String identificador) {}

    public List<Recurso> ordemProposta() {
        var ordem = new java.util.ArrayList<Recurso>();
        adicionar(ordem, Fase.PACIENTES, pacientes);
        adicionar(ordem, Fase.USUARIOS, usuarios);
        adicionar(ordem, Fase.VINCULOS, vinculos);
        adicionar(ordem, Fase.GRUPOS, grupos);
        adicionar(ordem, Fase.SESSOES, sessoes);
        adicionar(ordem, Fase.AGENDAMENTOS, agendamentos);
        return List.copyOf(ordem);
    }

    /** Mudança de conjunto requer abortar e redescobrir em nova transação. */
    public boolean mesmosRecursos(PlanoCoordenacaoEscritaClinica redescoberto) {
        return equals(Objects.requireNonNull(redescoberto, "Redescoberta obrigatória."));
    }

    private static <T extends Comparable<? super T>> List<T> ordenar(Collection<T> ids) {
        if (ids == null || ids.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Conjunto de recursos inválido.");
        return List.copyOf(new TreeSet<>(ids));
    }

    private static List<Long> ordenarIds(Collection<Long> ids) {
        var ordenados = ordenar(ids);
        if (ordenados.stream().anyMatch(id -> id <= 0))
            throw new IllegalArgumentException("Identificador persistido obrigatório.");
        return ordenados;
    }

    private static void adicionar(List<Recurso> ordem, Fase fase, List<?> ids) {
        ids.forEach(id -> ordem.add(new Recurso(fase, id.toString())));
    }
}
