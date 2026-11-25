package com.pet.buscaativa.services.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.dto.ConfirmarOcorrenciaGrupoDTO;
import com.pet.buscaativa.entities.dto.CorrigirFrequenciaDTO;
import com.pet.buscaativa.entities.dto.CorrigirFrequenciasGrupoDTO;
import com.pet.buscaativa.entities.dto.FrequenciaParticipanteGrupoDTO;
import com.pet.buscaativa.entities.dto.RegularizarFrequenciaCanceladaDTO;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import lombok.RequiredArgsConstructor;

/** Adaptador interno dos DTOs operacionais para os comandos locais.
 * Não registra controller nem substitui as interfaces públicas legadas.
 * Versão explícita no ensaio; compatibilidade de versão opcional será revisada no corte.
 */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
public class ComandosAssistenciaisLocaisEnsaioService {
    private final FrequenciaIndividualLocalEnsaioService individual;
    private final FrequenciaColetivaLocalEnsaioService coletivo;

    @Transactional
    public FrequenciaIndividualLocalEnsaioService.Resultado atualizarStatusIndividual(Long id,
            com.pet.buscaativa.entities.enums.SituacaoAtendimento destino, Integer version, String justificativa) {
        int v = versao(version);
        if (destino == null) throw invalido();
        return switch (destino) {
            case PRESENTE, FALTOU -> individual.atualizarFrequencia(id, v,
                    com.pet.buscaativa.entities.enums.FrequenciaAssistencial.valueOf(destino.name()), justificativa);
            case CANCELADO -> individual.cancelarReserva(id, v);
            default -> throw new ConflictException("Use o comando próprio de reserva ou remarcação.");
        };
    }

    @Transactional
    public FrequenciaIndividualLocalEnsaioService.Resultado recuperarReserva(Long id,
            com.pet.buscaativa.entities.dto.RecuperarReservaCanceladaDTO dto) {
        if (dto == null) throw invalido();
        return individual.recuperarReserva(id, versao(dto.version()), dto.justificativa());
    }

    @Transactional
    public FrequenciaIndividualLocalEnsaioService.Resultado corrigirIndividual(Long id, CorrigirFrequenciaDTO dto) {
        if (dto == null) throw invalido();
        return individual.corrigir(id, versao(dto.version()), dto.novaFrequencia(), dto.justificativa());
    }

    @Transactional
    public FrequenciaIndividualLocalEnsaioService.Resultado regularizarIndividual(Long id, RegularizarFrequenciaCanceladaDTO dto) {
        if (dto == null) throw invalido();
        return individual.regularizarCancelamento(id, versao(dto.version()), dto.novaFrequencia(), dto.justificativa());
    }

    @Transactional
    public int confirmarOcorrencia(Long id, ConfirmarOcorrenciaGrupoDTO dto) {
        if (dto == null || dto.ocorreu() == null) throw invalido();
        int v = versao(dto.version());
        if (!dto.ocorreu()) return coletivo.cancelar(id, v, dto.motivoCancelamento());
        return coletivo.confirmar(id, v, mapa(dto.frequencias()));
    }

    @Transactional
    public int corrigirColetiva(Long id, CorrigirFrequenciasGrupoDTO dto) {
        if (dto == null) throw invalido();
        return coletivo.corrigir(id, versao(dto.version()), mapa(dto.frequencias()), dto.justificativa());
    }

    private static int versao(Integer v) {
        if (v == null || v < 0) throw invalido();
        return v;
    }

    private static Map<java.util.UUID, StatusPresencaGrupo> mapa(List<FrequenciaParticipanteGrupoDTO> frequencias) {
        var mapa = new LinkedHashMap<java.util.UUID, StatusPresencaGrupo>();
        if (frequencias != null) for (var f : frequencias) {
            if (f == null || f.pacienteId() == null || f.statusPresenca() == null) throw invalido();
            if (mapa.putIfAbsent(f.pacienteId(), f.statusPresenca()) != null)
                throw new ConflictException("Participante duplicado nas frequências.");
        }
        return Map.copyOf(mapa);
    }

    private static ValidationException invalido() {
        return new ValidationException("Dados ou versão do comando inválidos.");
    }
}
