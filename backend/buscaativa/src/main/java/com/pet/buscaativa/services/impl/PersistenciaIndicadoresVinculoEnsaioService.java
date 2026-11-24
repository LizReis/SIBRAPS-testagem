package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.migration.PlanoIndicadoresVinculo;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class PersistenciaIndicadoresVinculoEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final EvidenciaStatusVinculo evidencia;
    private final CalculoIndicadoresVinculo calculo;
    private final EntityManager em;
    private final Clock clock;

    public enum Situacao { ATUALIZADO, SEM_ALTERACAO, INCONCLUSIVO }
    public record Resultado(Situacao situacao, boolean indicacaoDePendenciaAlterada, Integer versao) {}
    record Avaliado(VinculoAssistencial vinculo, PlanoIndicadoresVinculo.Plano plano, boolean fatosInconclusivos) {}

    @Transactional
    public Resultado recalcular(UUID paciente, UUID vinculo, Integer versao) {
        var bloqueados = coordenacao.paraVinculo(atores.autorizar(false), paciente, vinculo, null);
        var local = bloqueados.vinculos().get(paciente);
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, local.getVersion());
        return aplicar(avaliar(local), bloqueados.atores().ator());
    }

    EvidenciaStatusVinculo.Situacao evidencia(VinculoAssistencial local) {
        return evidencia.analisar(local);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Avaliado avaliar(VinculoAssistencial local) {
        return avaliar(local, evidencia(local));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Avaliado avaliar(VinculoAssistencial local, EvidenciaStatusVinculo.Situacao prova) {
        var data = LocalDate.now(clock);
        var valores = calculo.calcular(local, data, prova, local.getIndicadoresPendentes());
        var plano = PlanoIndicadoresVinculo.preparar(local.getIdPublico(), local.getUnidadeChave(), data,
                valores.status(), valores.tipo(), valores.pendencia(), valores.faltas(),
                valores.ultimaPresenca(), valores.risco(), valores.visita());
        return new Avaliado(local, plano, !valores.faltas().conclusiva()
                || valores.evidencias().agendaLocalInconclusiva());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Resultado aplicar(Avaliado avaliacao, Usuario ator) {
        var local = avaliacao.vinculo();
        var candidato = avaliacao.plano().candidato();
        if (candidato.isEmpty()) {
            // Preserva os valores conhecidos. A indicação explícita não é limpa
            // automaticamente, pois o modelo ainda não registra sua causa.
            boolean mudou = avaliacao.fatosInconclusivos() && local.sinalizarIndicadoresLocaisPendentes();
            if (mudou) registrar(local, ator, "Indicadores locais pendentes de conferência dos fatos.");
            return new Resultado(Situacao.INCONCLUSIVO, mudou, local.getVersion());
        }
        var valores = candidato.orElseThrow();
        boolean mudou = local.aplicarIndicadoresLocais(valores.faltas(), valores.ultimaPresenca().orElse(null),
                valores.risco(), valores.gatilhoVisita());
        if (mudou) registrar(local, ator, "Indicadores do acompanhamento local atualizados.");
        return new Resultado(mudou ? Situacao.ATUALIZADO : Situacao.SEM_ALTERACAO, false, local.getVersion());
    }

    private void registrar(VinculoAssistencial local, Usuario ator, String descricao) {
        var h = new HistoricoPaciente();
        h.setPaciente(local.getPaciente());
        h.setProfissional(ator);
        h.setTipo(TipoEventoHistoricoPaciente.SITUACAO_ATUALIZADA);
        h.setOcorridoEm(LocalDateTime.now(clock));
        h.setDescricao(descricao);
        h.registrarOrigemDeAcaoLocal(ator);
        em.persist(h);
        em.flush();
    }
}
