package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static com.pet.buscaativa.migration.PlanoCoordenacaoEscritaClinica.Fase.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.migration.PlanoCoordenacaoEscritaClinica;
import com.pet.buscaativa.migration.PlanoCoordenacaoEscritaClinica.Recurso;

class PlanoCoordenacaoEscritaClinicaTests {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void normalizaOrdemIndependentementeDaDescobertaEEliminaDuplicatas() {
        var plano = new PlanoCoordenacaoEscritaClinica(List.of(B, A, B), List.of(B, A),
                List.of(B, A), List.of(10L, 2L, 2L), List.of(8L, 3L), List.of(9L, 4L));
        assertEquals(List.of(new Recurso(PACIENTES, A.toString()), new Recurso(PACIENTES, B.toString()),
                new Recurso(USUARIOS, A.toString()), new Recurso(USUARIOS, B.toString()),
                new Recurso(VINCULOS, A.toString()), new Recurso(VINCULOS, B.toString()),
                new Recurso(GRUPOS, "2"), new Recurso(GRUPOS, "10"),
                new Recurso(SESSOES, "3"), new Recurso(SESSOES, "8"),
                new Recurso(AGENDAMENTOS, "4"), new Recurso(AGENDAMENTOS, "9")), plano.ordemProposta());
        assertTrue(plano.mesmosRecursos(new PlanoCoordenacaoEscritaClinica(List.of(A, B),
                List.of(A, B), List.of(A, B), List.of(2L, 10L), List.of(3L, 8L), List.of(4L, 9L))));
    }

    @Test
    void mudancaDeQualquerConjuntoExigeRedescoberta() {
        var plano = new PlanoCoordenacaoEscritaClinica(List.of(A), List.of(A), List.of(A),
                List.of(1L), List.of(2L), List.of(3L));
        for (var alterado : List.of(
                new PlanoCoordenacaoEscritaClinica(List.of(A,B), List.of(A), List.of(A), List.of(1L), List.of(2L), List.of(3L)),
                new PlanoCoordenacaoEscritaClinica(List.of(A), List.of(B), List.of(A), List.of(1L), List.of(2L), List.of(3L)),
                new PlanoCoordenacaoEscritaClinica(List.of(A), List.of(A), List.of(B), List.of(1L), List.of(2L), List.of(3L)),
                new PlanoCoordenacaoEscritaClinica(List.of(A), List.of(A), List.of(A), List.of(), List.of(2L), List.of(3L)),
                new PlanoCoordenacaoEscritaClinica(List.of(A), List.of(A), List.of(A), List.of(1L), List.of(4L), List.of(3L)),
                new PlanoCoordenacaoEscritaClinica(List.of(A), List.of(A), List.of(A), List.of(1L), List.of(2L), List.of(3L,4L))))
            assertFalse(plano.mesmosRecursos(alterado));
    }

    @Test
    void planoImutavelEIdsInvalidosRecusados() {
        var ids = new ArrayList<>(List.of(A));
        var plano = new PlanoCoordenacaoEscritaClinica(ids, List.of(), List.of(), List.of(), List.of(), List.of());
        ids.add(B);
        assertEquals(List.of(A), plano.pacientes());
        assertThrows(UnsupportedOperationException.class, () -> plano.pacientes().add(B));
        assertThrows(UnsupportedOperationException.class, () -> plano.ordemProposta().clear());
        assertThrows(IllegalArgumentException.class, () -> new PlanoCoordenacaoEscritaClinica(
                Arrays.asList(A, null), List.of(), List.of(), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PlanoCoordenacaoEscritaClinica(
                List.of(A), List.of(), List.of(), List.of(-1L), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PlanoCoordenacaoEscritaClinica(
                List.of(A), null, List.of(), List.of(), List.of(), List.of()));
    }
}
