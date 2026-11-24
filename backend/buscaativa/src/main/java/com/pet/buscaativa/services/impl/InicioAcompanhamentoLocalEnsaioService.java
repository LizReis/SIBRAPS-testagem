package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.EventoEstadoClinicoVinculo;
import com.pet.buscaativa.entities.EventoEstadoClinicoVinculo.Natureza;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Ensaio interno de criação inicial de vínculo em base sintética.
 * Não é rota, nem substitui a confirmação cadastral existente.
 * O lock da identidade serializa criações de vínculo; a integração futura
 * deverá coordenar também os escritores de fatos que não usam essa trava.
 */
@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class InicioAcompanhamentoLocalEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final AgendamentoRepository agendamentos;
    private final PacienteRepository pacientes;
    private final VinculoAssistencialRepository vinculos;
    private final HistoricoPacienteRepository historicos;
    private final EntityManager em;
    private final Clock clock;

    public enum Situacao { CRIADO_NO_ENSAIO, JA_EXISTENTE_NAO_MODIFICADO }
    public record Resultado(UUID vinculo, Situacao situacao) {}

    @Transactional
    public Resultado iniciar(UUID pacienteId, TipoAcompanhamento tipo, UUID referenciaId) {
        var contexto = atores.autorizar(false);
        String unidade = contexto.unidade();
        if (pacienteId == null) throw new ResourceNotFoundException("Paciente não encontrado.");
        if (tipo == null) throw new ValidationException("Tipo de acompanhamento obrigatório.");
        var pessoa = pacientes.findByIdPublicoForUpdate(pacienteId)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado."));

        var existente = vinculos.findByPacienteIdAndUnidadeChave(pessoa.getId(), unidade);
        var autorizados = atores.bloquear(contexto, existente.isPresent() ? null : referenciaId);
        if (existente.isPresent())
            return new Resultado(existente.get().getIdPublico(), Situacao.JA_EXISTENTE_NAO_MODIFICADO);

        var ator = autorizados.ator();
        // Mais conservador que contar frequências: até reservas e históricos
        // sem origem podem indicar acompanhamento anterior. Não expor os fatos.
        if (temFatosAnteriores(pessoa.getId(), unidade))
            throw new ValidationException("Não foi possível comprovar acompanhamento novo.");

        var local = ator.getUnidadeAtuacao() == UnidadeAtuacao.USF
                ? VinculoAssistencial.paraUsf(pessoa, ator.getUsfAtuacao())
                : VinculoAssistencial.paraCaps(pessoa, ator.getUnidadeAtuacao());
        var instante = LocalDateTime.now(clock);
        var diagnostico = new LinhaDoTempoFrequenciasVinculoService.Diagnostico(List.of(), 0, 0, 0, 0);
        var risco = ComparadorPoliticaRiscoVinculo.comparar(
                DiagnosticoFaltasConsecutivasVinculoService.calcular(diagnostico),
                StatusPaciente.ATIVO, tipo, false, instante.toLocalDate()).orElseThrow();
        local.inicializarNovoAcompanhamento(tipo, autorizados.referencia(), risco);
        vinculos.saveAndFlush(local);

        em.persist(new EventoEstadoClinicoVinculo(local, ator, instante, Natureza.INICIALIZACAO,
                null, null, null));
        var h = new HistoricoPaciente();
        h.setPaciente(pessoa);
        h.setProfissional(ator);
        h.setTipo(TipoEventoHistoricoPaciente.SITUACAO_ATUALIZADA);
        h.setOcorridoEm(instante);
        h.setDescricao("Início explícito do acompanhamento local no ensaio.");
        h.registrarOrigemDeAcaoLocal(ator);
        historicos.saveAndFlush(h);
        return new Resultado(local.getIdPublico(), Situacao.CRIADO_NO_ENSAIO);
    }

    private boolean temFatosAnteriores(Long paciente, String unidade) {
        return em.createQuery("select a from Agendamento a where a.paciente.id = :paciente", Agendamento.class)
                .setParameter("paciente", paciente).getResultList().stream()
                .anyMatch(a -> !agendaExternaComprovada(a, paciente, unidade))
                || em.createQuery("""
                        select p from SessaoGrupoParticipante p where p.paciente.id = :paciente
                        """, SessaoGrupoParticipante.class).setParameter("paciente", paciente).getResultList().stream()
                        .anyMatch(p -> AvaliacaoProvenienciaClinica.origemSessao(p.getSessaoGrupo())
                                != AvaliacaoProvenienciaClinica.Situacao.COMPROVADA
                                || !origemExterna(p.getSessaoGrupo().getUnidadeOrigemChave(), unidade))
                || em.createQuery("select h from HistoricoPaciente h where h.paciente.id = :paciente",
                        HistoricoPaciente.class).setParameter("paciente", paciente).getResultList().stream()
                        .anyMatch(h -> !historicoExternoComprovado(h, paciente, unidade));
    }

    private boolean agendaExternaComprovada(Agendamento a, Long paciente, String unidade) {
        return origemExterna(a.getUnidadeOrigemChave(), unidade)
                && AvaliacaoProvenienciaClinica.cadeiaAgendamento(a, paciente, a.getUnidadeOrigemChave(),
                        (original, sucessor) -> agendamentos.existsByAgendamentoOriginal_IdAndIdNot(
                                original.getId(), sucessor.getId()))
                == AvaliacaoProvenienciaClinica.Situacao.COMPROVADA;
    }

    private boolean historicoExternoComprovado(HistoricoPaciente h, Long paciente, String unidade) {
        String origem = h.getUnidadeOrigemChave();
        if (!origemExterna(origem, unidade)
                || h.getAgendamento() != null && h.getSessaoGrupo() != null) return false;
        if (h.getAgendamento() != null) {
            return origem.equals(h.getAgendamento().getUnidadeOrigemChave())
                    && agendaExternaComprovada(h.getAgendamento(), paciente, unidade);
        }
        if (h.getSessaoGrupo() != null) {
            var sessao = h.getSessaoGrupo();
            return origem.equals(sessao.getUnidadeOrigemChave())
                    && AvaliacaoProvenienciaClinica.origemSessao(sessao)
                            == AvaliacaoProvenienciaClinica.Situacao.COMPROVADA
                    && sessao.getStatus() == StatusSessaoGrupo.REALIZADA
                    && sessao.getParticipantes().stream().anyMatch(p -> Objects.equals(paciente, p.getPaciente().getId())
                            && AvaliacaoProvenienciaClinica.frequenciaRegistrada(p.getStatusPresenca()));
        }
        return AvaliacaoProvenienciaClinica.acaoLocalSemFonteRegistrada(h);
    }

    private static boolean origemExterna(String origem, String unidade) {
        return origem != null && !unidade.equals(origem)
                && (origem.matches("USF:[1-9][0-9]*") || origem.equals(UnidadeAtuacao.CAPS_I.name())
                    || origem.equals(UnidadeAtuacao.CAPS_II.name()) || origem.equals(UnidadeAtuacao.CAPS_AD.name()));
    }
}
