package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.dto.RegistroHistoricoPacienteDTO;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.services.exceptions.ValidationException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Registro narrativo local, nunca fonte de frequência efetiva. */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
public class HistoricoManualLocalEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final EntityManager em;
    private final Clock clock;

    @Transactional
    public Long registrar(UUID paciente, UUID vinculo, Integer versao, RegistroHistoricoPacienteDTO dados) {
        var recursos = coordenacao.paraVinculo(atores.autorizar(false), paciente, vinculo, null);
        var local = recursos.vinculos().get(paciente);
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, local.getVersion());
        if (dados == null || (dados.tipo() != TipoEventoHistoricoPaciente.BUSCA_ATIVA
                && dados.tipo() != TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO))
            throw new ValidationException("Somente busca ativa ou participação em grupo podem ser registradas manualmente.");
        if (dados.tipo() == TipoEventoHistoricoPaciente.BUSCA_ATIVA
                && (dados.descricao() == null || dados.descricao().isBlank()))
            throw new ValidationException("Descrição da busca ativa obrigatória.");
        if (dados.descricao() != null && dados.descricao().length() > 2000)
            throw new ValidationException("Descrição excede o limite do histórico.");
        var h = new HistoricoPaciente();
        h.setPaciente(local.getPaciente());
        h.setProfissional(recursos.atores().ator());
        h.setTipo(dados.tipo());
        h.setDescricao(dados.descricao());
        h.setOcorridoEm(dados.ocorridoEm() == null ? LocalDateTime.now(clock) : dados.ocorridoEm());
        h.registrarOrigemDeAcaoLocal(recursos.atores().ator());
        em.persist(h);
        em.flush();
        return h.getId();
    }
}
