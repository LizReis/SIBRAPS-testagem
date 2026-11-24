package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.EventoEstadoClinicoVinculo;
import com.pet.buscaativa.entities.EventoEstadoClinicoVinculo.Natureza;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.dto.EncerramentoPacienteDTO;
import com.pet.buscaativa.entities.enums.MotivoEncerramento;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ValidationException;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Ensaio de escrita local, exclusivamente no perfil de teste explícito.
 * Não é chamado pelo cadastro, rotas clínicas, seed ou diagnóstico read-only.
 * Preserva o estado global e executa o cálculo local sob as mesmas travas.
 */
@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class EstadoClinicoLocalEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final PersistenciaIndicadoresVinculoEnsaioService indicadores;
    private final EntityManager em;
    private final VinculoAssistencialRepository vinculos;
    private final HistoricoPacienteRepository historicos;
    private final Clock clock;

    public record Resultado(UUID vinculo, StatusPaciente status, TipoAcompanhamento tipo, Integer versao) {}

    @Transactional
    public Resultado configurar(UUID paciente, UUID vinculo, Integer versao,
            TipoAcompanhamento tipo, UUID referencia) {
        // Restrição conservadora deste ensaio, não uma nova política de edição HTTP.
        var bloqueados = coordenacao.paraVinculo(atores.autorizar(true), paciente, vinculo, referencia);
        var autorizados = bloqueados.atores();
        Usuario ator = autorizados.ator();
        var local = bloqueados.vinculos().get(paciente);
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, local.getVersion());
        if (tipo == null) throw new ValidationException("Tipo de acompanhamento obrigatório.");
        Usuario profissional = autorizados.referencia();
        Long anterior = local.getProfissionalReferencia() == null ? null : local.getProfissionalReferencia().getId();
        Long novo = profissional == null ? null : profissional.getId();
        if (tipo == local.getTipoAcompanhamento() && Objects.equals(anterior, novo)) return resultado(local);
        String descricao = "Acompanhamento local: " + local.getTipoAcompanhamento() + " -> " + tipo
                + ". Profissional de referência " + (Objects.equals(anterior, novo) ? "mantido." : "alterado.");
        local.definirAcompanhamentoLocal(tipo, profissional);
        indicadores.aplicar(indicadores.avaliar(local), ator);
        return registrar(local, ator, descricao, LocalDateTime.now(clock));
    }

    @Transactional
    public Resultado encerrar(UUID paciente, UUID vinculo, Integer versao, EncerramentoPacienteDTO dados) {
        var bloqueados = coordenacao.paraVinculo(atores.autorizar(false), paciente, vinculo, null);
        Usuario ator = bloqueados.atores().ator();
        var local = bloqueados.vinculos().get(paciente);
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, local.getVersion());
        if (dados == null || dados.motivo() == null || dados.statusPaciente() == null
                || dados.statusPaciente() == StatusPaciente.ATIVO || dados.statusPaciente() == local.getStatusPaciente())
            throw new ValidationException("Encerramento local inválido.");
        if (dados.motivo() == MotivoEncerramento.OUTRO
                && (dados.descricao() == null || dados.descricao().isBlank()))
            throw new ValidationException("Descrição obrigatória para encerramento OUTRO.");
        String descricao = "Encerramento local: " + local.getStatusPaciente() + " -> " + dados.statusPaciente()
                + ". Motivo: " + dados.motivo()
                + (dados.descricao() == null || dados.descricao().isBlank() ? "." : ". " + dados.descricao().trim());
        validarDescricao(descricao);
        var provaAnterior = indicadores.evidencia(local);
        var anterior = local.getStatusPaciente();
        local.alterarStatusLocal(dados.statusPaciente());
        var instante = LocalDateTime.now(clock);
        String complemento = dados.descricao() == null || dados.descricao().isBlank() ? null : dados.descricao().trim();
        em.persist(new EventoEstadoClinicoVinculo(local, ator, instante, Natureza.ENCERRAMENTO,
                anterior, dados.motivo().name(), complemento));
        if (provaAnterior.comprovada()) {
            var avaliacao = indicadores.avaliar(local, EvidenciaStatusVinculo.Situacao.TRANSICAO_AUDITADA);
            // O encerramento legado desativa visita e grava verde. Aplicamos apenas
            // quando o candidato conclusivo da projeção também respeita esses efeitos.
            var candidato = avaliacao.plano().candidato();
            if (candidato.isEmpty() || candidato.filter(c -> !c.gatilhoVisita()
                    && c.risco() == com.pet.buscaativa.entities.enums.ClassificacaoRisco.VERDE).isPresent())
                indicadores.aplicar(avaliacao, ator);
        }
        return registrar(local, ator, descricao, instante);
    }

    @Transactional
    public Resultado reativar(UUID paciente, UUID vinculo, Integer versao, String motivo) {
        var bloqueados = coordenacao.paraVinculo(atores.autorizar(true), paciente, vinculo, null);
        Usuario ator = bloqueados.atores().ator();
        var local = bloqueados.vinculos().get(paciente);
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, local.getVersion());
        if (local.getStatusPaciente() == null || local.getStatusPaciente() == StatusPaciente.ATIVO)
            throw new ValidationException("Reativação exige status local não ativo registrado.");
        if (motivo == null || motivo.isBlank()) throw new ValidationException("Motivo de reativação obrigatório.");
        String descricao = "Reativação local: " + local.getStatusPaciente() + " -> ATIVO. Motivo: " + motivo.trim();
        validarDescricao(descricao);
        var provaAnterior = indicadores.evidencia(local);
        var anterior = local.getStatusPaciente();
        local.alterarStatusLocal(StatusPaciente.ATIVO);
        var instante = LocalDateTime.now(clock);
        em.persist(new EventoEstadoClinicoVinculo(local, ator, instante, Natureza.REATIVACAO,
                anterior, motivo.trim(), null));
        // A revisão Envers do comando ainda será escrita no commit. Só
        // antecipamos o cálculo se a cadeia anterior já era comprovada.
        if (provaAnterior.comprovada())
            indicadores.aplicar(indicadores.avaliar(local, EvidenciaStatusVinculo.Situacao.TRANSICAO_AUDITADA), ator);
        return registrar(local, ator, descricao, instante);
    }

    private Resultado registrar(VinculoAssistencial local, Usuario ator, String descricao, LocalDateTime instante) {
        validarDescricao(descricao);
        var h = new HistoricoPaciente();
        h.setPaciente(local.getPaciente());
        h.setProfissional(ator);
        h.setTipo(TipoEventoHistoricoPaciente.SITUACAO_ATUALIZADA);
        h.setOcorridoEm(instante);
        h.setDescricao(descricao);
        h.registrarOrigemDeAcaoLocal(ator);
        historicos.save(h);
        vinculos.saveAndFlush(local);
        return resultado(local);
    }

    private static void validarDescricao(String descricao) {
        if (descricao.length() > 2000) throw new ValidationException("Descrição excede o limite do histórico.");
    }

    private static Resultado resultado(VinculoAssistencial local) {
        return new Resultado(local.getIdPublico(), local.getStatusPaciente(), local.getTipoAcompanhamento(), local.getVersion());
    }
}
