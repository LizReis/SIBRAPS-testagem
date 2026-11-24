package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Ensaio interno de criação inicial de vínculo em base sintética.
 * Não é rota, nem substitui a confirmação cadastral existente.
 * O lock da identidade serializa criações de vínculo; a integração futura
 * deverá coordenar também escritores de fatos clínicos e mudanças de lotação.
 */
@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class InicioAcompanhamentoLocalEnsaioService {
    private final EscopoUnidadeService escopo;
    private final UsuarioRepository usuarios;
    private final PacienteRepository pacientes;
    private final VinculoAssistencialRepository vinculos;
    private final HistoricoPacienteRepository historicos;
    private final EntityManager em;
    private final Clock clock;

    public enum Situacao { CRIADO_NO_ENSAIO, JA_EXISTENTE_NAO_MODIFICADO }
    public record Resultado(UUID vinculo, Situacao situacao) {}

    @Transactional
    public Resultado iniciar(UUID pacienteId, TipoAcompanhamento tipo, UUID referenciaId) {
        escopo.validarUnidadeConfigurada();
        var principal = escopo.usuarioLogado();
        if (principal.getId() == null) throw new AccessDeniedException("Usuário não identificado.");
        var ator = usuarios.findById(principal.getId())
                .orElseThrow(() -> new AccessDeniedException("Usuário não identificado."));
        String unidade = ChaveUnidadeAssistencial.doUsuarioAtual(principal);
        if (!pertence(ator, unidade) || !clinico(ator))
            throw new AccessDeniedException("Perfil ou unidade não autorizado para este ensaio.");
        if (pacienteId == null) throw new ResourceNotFoundException("Paciente não encontrado.");
        if (tipo == null) throw new ValidationException("Tipo de acompanhamento obrigatório.");
        var pessoa = pacientes.findByIdPublicoForUpdate(pacienteId)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado."));

        var existente = vinculos.findByPacienteIdAndUnidadeChave(pessoa.getId(), unidade);
        if (existente.isPresent())
            return new Resultado(existente.get().getIdPublico(), Situacao.JA_EXISTENTE_NAO_MODIFICADO);

        Usuario referencia = referenciaId == null ? null : usuarios.findByIdPublico(referenciaId)
                .filter(InicioAcompanhamentoLocalEnsaioService::clinico)
                .filter(u -> pertence(u, unidade))
                .orElseThrow(() -> new ResourceNotFoundException("Profissional de referência não encontrado."));

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
        local.inicializarNovoAcompanhamento(tipo, referencia, risco);
        vinculos.saveAndFlush(local);

        // Trilha real de criação, não uma transição artificial. Ainda não é
        // marcador estruturado suficiente para alterar a prova da projeção.
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
        return contar("""
                select count(a) from Agendamento a
                where a.paciente.id = :paciente
                  and (a.unidadeOrigemChave = :unidade or a.unidadeOrigemChave is null
                       or trim(a.unidadeOrigemChave) = '')
                """, paciente, unidade) > 0
                || contar("""
                select count(p) from SessaoGrupoParticipante p
                join p.sessaoGrupo s join s.grupo g
                where p.paciente.id = :paciente
                  and (s.unidadeOrigemChave = :unidade or s.unidadeOrigemChave is null
                       or trim(s.unidadeOrigemChave) = ''
                       or g.unidadeOrigemChave = :unidade or g.unidadeOrigemChave is null
                       or trim(g.unidadeOrigemChave) = '')
                """, paciente, unidade) > 0
                || contar("""
                select count(h) from HistoricoPaciente h
                left join h.agendamento a left join h.sessaoGrupo s left join s.grupo g
                where h.paciente.id = :paciente
                  and (h.unidadeOrigemChave = :unidade or h.unidadeOrigemChave is null
                       or trim(h.unidadeOrigemChave) = ''
                       or (a.id is not null and (a.unidadeOrigemChave = :unidade
                           or a.unidadeOrigemChave is null or trim(a.unidadeOrigemChave) = ''))
                       or (s.id is not null and (s.unidadeOrigemChave = :unidade
                           or s.unidadeOrigemChave is null or trim(s.unidadeOrigemChave) = ''
                           or g.id is null or g.unidadeOrigemChave = :unidade
                           or g.unidadeOrigemChave is null or trim(g.unidadeOrigemChave) = '')))
                """, paciente, unidade) > 0;
    }

    private long contar(String jpql, Long paciente, String unidade) {
        return em.createQuery(jpql, Long.class).setParameter("paciente", paciente)
                .setParameter("unidade", unidade).getSingleResult();
    }

    private static boolean clinico(Usuario u) {
        return u.getTipoUsuario() == TipoUsuario.ADMINISTRADOR || u.getTipoUsuario() == TipoUsuario.PROFISSIONAL;
    }

    private static boolean pertence(Usuario u, String unidade) {
        try { return unidade.equals(ChaveUnidadeAssistencial.doUsuarioAtual(u)); }
        catch (IllegalArgumentException | NullPointerException e) { return false; }
    }
}
