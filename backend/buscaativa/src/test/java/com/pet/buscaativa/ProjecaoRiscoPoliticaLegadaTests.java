package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.impl.ComparadorPoliticaRiscoVinculo;
import com.pet.buscaativa.services.impl.DiagnosticoFaltasConsecutivasVinculoService;
import com.pet.buscaativa.services.impl.PacienteServiceImpl;

class ProjecaoRiscoPoliticaLegadaTests {
    private static final LocalDate REFERENCIA = LocalDate.of(2026, 11, 23);

    @ParameterizedTest
    @MethodSource("casos")
    void replicaRiscoLegadoParaEntradasEquivalentes(TipoAcompanhamento tipo, Integer dias,
            int faltas, StatusPaciente status, ClassificacaoRisco esperado) {
        LocalDate ultima = dias == null ? null : REFERENCIA.minusDays(dias);
        var paciente = new Paciente();
        paciente.setStatusPaciente(status);
        paciente.setTipoAcompanhamento(tipo);
        paciente.setDataUltimaPresenca(ultima);
        paciente.setCountFaltas(faltas);
        paciente.setIndicadoresPendentes(false);

        // Entidade transitória: exercita a política real sem paciente persistido ou evento operacional.
        var legado = mock(PacienteServiceImpl.class, CALLS_REAL_METHODS);
        var tipoModelo = ReflectionUtils.findField(PacienteServiceImpl.class, "modelo").getType();
        ReflectionTestUtils.setField(legado, "modelo", mock(tipoModelo));
        ReflectionTestUtils.setField(legado, "clock",
                Clock.fixed(REFERENCIA.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        legado.calcularEAtualizarRisco(paciente);

        var diagnostico = new DiagnosticoFaltasConsecutivasVinculoService.Resultado(OptionalInt.of(faltas),
                Optional.ofNullable(ultima).map(LocalDate::atStartOfDay), faltas, false, 0, 0, 0, false);
        assertEquals(esperado, paciente.getClassificacaoRisco());
        assertEquals(Optional.of(esperado),
                ComparadorPoliticaRiscoVinculo.comparar(diagnostico, status, tipo, false, REFERENCIA));
    }

    static Stream<Arguments> casos() {
        var porDias = Stream.of(TipoAcompanhamento.INDIVIDUAL, TipoAcompanhamento.GRUPO_TERAPEUTICO,
                TipoAcompanhamento.AMBOS).flatMap(tipo -> {
            int amarelo = tipo == TipoAcompanhamento.INDIVIDUAL ? 60 : 15;
            int vermelho = tipo == TipoAcompanhamento.INDIVIDUAL ? 120 : 30;
            return Stream.of(
                    Arguments.of(tipo, amarelo - 1, 0, StatusPaciente.ATIVO, ClassificacaoRisco.VERDE),
                    Arguments.of(tipo, amarelo, 0, StatusPaciente.ATIVO, ClassificacaoRisco.VERDE),
                    Arguments.of(tipo, amarelo + 1, 0, StatusPaciente.ATIVO, ClassificacaoRisco.AMARELO),
                    Arguments.of(tipo, vermelho - 1, 0, StatusPaciente.ATIVO, ClassificacaoRisco.AMARELO),
                    Arguments.of(tipo, vermelho, 0, StatusPaciente.ATIVO, ClassificacaoRisco.AMARELO),
                    Arguments.of(tipo, vermelho + 1, 0, StatusPaciente.ATIVO, ClassificacaoRisco.VERMELHO));
        });
        var porFaltas = Stream.of(
                Arguments.of(TipoAcompanhamento.INDIVIDUAL, null, 0, StatusPaciente.ATIVO, ClassificacaoRisco.VERDE),
                Arguments.of(TipoAcompanhamento.INDIVIDUAL, null, 1, StatusPaciente.ATIVO, ClassificacaoRisco.VERDE),
                Arguments.of(TipoAcompanhamento.INDIVIDUAL, null, 2, StatusPaciente.ATIVO, ClassificacaoRisco.AMARELO),
                Arguments.of(TipoAcompanhamento.INDIVIDUAL, null, 3, StatusPaciente.ATIVO, ClassificacaoRisco.VERMELHO),
                Arguments.of(TipoAcompanhamento.INDIVIDUAL, null, 4, StatusPaciente.ATIVO, ClassificacaoRisco.VERMELHO),
                Arguments.of(TipoAcompanhamento.INDIVIDUAL, 121, 3, StatusPaciente.INATIVO, ClassificacaoRisco.VERDE));
        return Stream.concat(porDias, porFaltas);
    }
}
