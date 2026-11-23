package com.pet.buscaativa.services.impl;

import java.util.Objects;

import org.hibernate.envers.query.AuditEntity;
import org.hibernate.envers.query.criteria.AuditCriterion;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO.UnidadePainelDTO;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;

/** Escopo de dados independente das permissões funcionais de cada role. */
@Service
@RequiredArgsConstructor
public class EscopoUnidadeService {
    private final UsuarioRepository usuarioRepository;
    private final VinculoAssistencialRepository vinculos;

    public Usuario usuarioLogado() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new AccessDeniedException("Usuário autenticado não identificado.");
        }
        // O filtro JWT existente recarrega este principal do banco a cada requisição.
        if (auth.getPrincipal() instanceof Usuario usuario) return usuario;
        return usuarioRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Usuário autenticado não identificado."));
    }

    private Usuario usuarioComUnidade() {
        Usuario usuario = usuarioLogado();
        if (usuario.getUnidadeAtuacao() == null) {
            throw new ValidationException("A unidade de atuação deste usuário ainda não foi configurada.");
        }
        if (usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                && (usuario.getUsfAtuacao() == null || usuario.getUsfAtuacao().getId() == null)) {
            throw new ValidationException("A unidade USF específica deste usuário ainda não foi configurada.");
        }
        return usuario;
    }

    public void validarUnidadeConfigurada() {
        usuarioComUnidade();
    }

    /**
     * Barreira conservadora de transicao para rotas clinicas LEGADAS.
     *
     * Pacientes ainda sem qualquer vinculo permanecem no filtro historico
     * temporario. Quando ao menos um vinculo explicito existe, a referencia
     * territorial deixa de ser suficiente: exigir vinculo da propria unidade
     * e NEGAR se houver mais de uma unidade vinculada. Mesmo nesse caso
     * mantemos o filtro territorial anterior como limite adicional, pois o
     * prontuario global ainda nao foi dividido por unidade.
     *
     * Isso nao equivale ao corte final: pacientes legados sem vinculo e
     * auditorias/relatorios especiais continuam exigindo migracao completa.
     */
    public <T> Specification<T> specificationPaciente(String... caminho) {
        Usuario usuario = usuarioComUnidade();
        String chaveLocal = ChaveUnidadeAssistencial.doUsuarioAtual(usuario);
        return (root, query, cb) -> {
            Path<?> paciente = root;
            for (String parte : caminho) paciente = paciente.get(parte);

            var referenciaAnterior = usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                    ? cb.equal(paciente.get("usfReferencia").get("id"), usuario.getUsfAtuacao().getId())
                    : cb.equal(paciente.get("capsReferencia"), CapsEnum.valueOf(usuario.getUnidadeAtuacao().name()));

            var qualquer = query.subquery(Long.class);
            Root<VinculoAssistencial> v1 = qualquer.from(VinculoAssistencial.class);
            qualquer.select(v1.get("id")).where(cb.equal(v1.get("paciente").get("id"), paciente.get("id")));

            var local = query.subquery(Long.class);
            Root<VinculoAssistencial> v2 = local.from(VinculoAssistencial.class);
            local.select(v2.get("id")).where(
                    cb.equal(v2.get("paciente").get("id"), paciente.get("id")),
                    cb.equal(v2.get("unidadeChave"), chaveLocal));

            var outro = query.subquery(Long.class);
            Root<VinculoAssistencial> v3 = outro.from(VinculoAssistencial.class);
            outro.select(v3.get("id")).where(
                    cb.equal(v3.get("paciente").get("id"), paciente.get("id")),
                    cb.notEqual(v3.get("unidadeChave"), chaveLocal));

            // Sem vinculos: manter legado, nao fazer backfill por referencia.
            // Com vinculos: somente unidade local UNICA e ainda limitada
            // pelo alcance clinico anterior; multiplas unidades falham fechadas.
            var transicao = cb.or(cb.not(cb.exists(qualquer)),
                    cb.and(cb.exists(local), cb.not(cb.exists(outro))));
            return cb.and(referenciaAnterior, transicao);
        };
    }

    public <T> Specification<T> specificationUsuario(String... caminho) {
        Usuario usuario = usuarioComUnidade();
        return (root, query, cb) -> {
            Path<?> alvo = root;
            for (String parte : caminho) alvo = alvo.get(parte);
            var unidade = cb.equal(alvo.get("unidadeAtuacao"), usuario.getUnidadeAtuacao());
            return usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                    ? cb.and(unidade, cb.equal(alvo.get("usfAtuacao").get("id"), usuario.getUsfAtuacao().getId()))
                    : unidade;
        };
    }

    public AuditCriterion criterioPacienteAuditoria() {
        Usuario usuario = usuarioComUnidade();
        return usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                ? AuditEntity.relatedId("usfReferencia").eq(usuario.getUsfAtuacao().getId())
                : AuditEntity.property("capsReferencia").eq(CapsEnum.valueOf(usuario.getUnidadeAtuacao().name()));
    }

    /**
     * Valida SOMENTE a criacao inicial de um cadastro transiente.
     *
     * Nao pode autorizar leitura, atualizacao ou consulta clinica: essas
     * operacoes exigem paciente persistido e passam pela barreira de
     * vinculo. Durante a transicao o POST legado ainda requer que a
     * referencia fornecida seja compativel com a unidade autenticada.
     * Essa referencia NAO cria vinculo para a unidade mencionada.
     */
    public void validarEscopoDoNovoCadastro(Paciente candidato) {
        Usuario usuario = usuarioComUnidade();
        if (candidato == null || candidato.getId() != null)
            throw new ResourceNotFoundException("Paciente não encontrado.");

        boolean referenciaAnterior = usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                ? candidato.getUsfReferencia() != null
                    && Objects.equals(candidato.getUsfReferencia().getId(), usuario.getUsfAtuacao().getId())
                : candidato.getCapsReferencia() == CapsEnum.valueOf(usuario.getUnidadeAtuacao().name());
        if (!referenciaAnterior)
            throw new ResourceNotFoundException("Paciente não encontrado.");
    }

    public boolean pacientePertenceAoEscopo(Paciente paciente) {
        Usuario usuario = usuarioComUnidade();
        if (paciente == null) return false;
        boolean referenciaAnterior = usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                ? paciente.getUsfReferencia() != null
                    && Objects.equals(paciente.getUsfReferencia().getId(), usuario.getUsfAtuacao().getId())
                : paciente.getCapsReferencia() == CapsEnum.valueOf(usuario.getUnidadeAtuacao().name());
        if (!referenciaAnterior || paciente.getId() == null) return false;

        // Mesma politica da Specification para evitar bypass por UUID ou
        // acesso indireto a historico/agendamentos nos servicos legados.
        if (!vinculos.existsByPacienteId(paciente.getId())) return true;
        String chaveLocal = ChaveUnidadeAssistencial.doUsuarioAtual(usuario);
        return vinculos.existsByPacienteIdAndUnidadeChave(paciente.getId(), chaveLocal)
                && !vinculos.existsByPacienteIdAndUnidadeChaveNot(paciente.getId(), chaveLocal);
    }

    public boolean usuarioPertenceAoEscopo(Usuario alvo) {
        Usuario usuario = usuarioComUnidade();
        if (alvo == null || alvo.getUnidadeAtuacao() != usuario.getUnidadeAtuacao()) return false;
        return usuario.getUnidadeAtuacao() != UnidadeAtuacao.USF || alvo.getUsfAtuacao() != null
                && Objects.equals(alvo.getUsfAtuacao().getId(), usuario.getUsfAtuacao().getId());
    }

    public void validarAcesso(Paciente paciente) {
        if (!pacientePertenceAoEscopo(paciente)) throw new ResourceNotFoundException("Paciente não encontrado.");
    }

    public void validarUsuario(Usuario usuario) {
        if (!usuarioPertenceAoEscopo(usuario)) throw new ResourceNotFoundException("Usuário não encontrado.");
    }

    /**
     * Barreira de escrita/leitura para agendamentos nas rotas LEGADAS.
     *
     * Se existe vinculo explicito, a identidade global e a lotacao atual
     * do profissional nao autorizam alterar um fato de origem externa ou
     * desconhecida. Exigir proveniencia local em TODA a cadeia original,
     * inclusive para GET por ID, alteracao de status e correcao de frequencia.
     *
     * Pacientes ainda sem vinculos permanecem na regra de escopo antiga
     * ate a migracao/revisao humana de seus registros. Nenhuma origem
     * historica e reconstruida ou modificada neste verificador.
     */
    public boolean agendamentoPertenceAoEscopo(Agendamento agendamento) {
        if (agendamento == null || agendamento.getPaciente() == null
                || agendamento.getUsuario() == null
                || !pacientePertenceAoEscopo(agendamento.getPaciente())
                || !usuarioPertenceAoEscopo(agendamento.getUsuario())) return false;

        Long pacienteId = agendamento.getPaciente().getId();
        if (pacienteId == null) return false;
        if (!vinculos.existsByPacienteId(pacienteId)) return true;

        String unidade = ChaveUnidadeAssistencial.doUsuarioAtual(usuarioComUnidade());
        var visitados = new java.util.HashSet<Long>();
        Agendamento elo = agendamento;
        while (elo != null) {
            if (elo.getId() == null || !visitados.add(elo.getId())
                    || elo.getPaciente() == null
                    || !Objects.equals(elo.getPaciente().getId(), pacienteId)
                    || !Objects.equals(elo.getUnidadeOrigemChave(), unidade))
                return false;
            elo = elo.getAgendamentoOriginal();
        }
        return true;
    }

    public void validarAgendamento(Agendamento agendamento) {
        if (!agendamentoPertenceAoEscopo(agendamento)) throw new ResourceNotFoundException("Agendamento não encontrado.");
    }

    /**
     * Grupos NOVOS com origem registrada continuam pertencendo a unidade
     * onde foram criados, mesmo que a lotacao do coordenador mude depois.
     * O escopo antigo do coordenador permanece como uma segunda barreira.
     *
     * A origem NULL do grupo legado nao e reconstruida: a revisao humana
     * desses fatos continua pendente e o filtro legado nao e ampliado.
     */
    public boolean grupoPertenceAoEscopo(GrupoTerapeutico grupo) {
        if (grupo == null || grupo.getCoordenador() == null
                || !usuarioPertenceAoEscopo(grupo.getCoordenador()))
            return false;
        String origem = grupo.getUnidadeOrigemChave();
        return origem == null || Objects.equals(origem,
                ChaveUnidadeAssistencial.doUsuarioAtual(usuarioComUnidade()));
    }

    public void validarGrupo(GrupoTerapeutico grupo) {
        if (!grupoPertenceAoEscopo(grupo)) throw new ResourceNotFoundException("Grupo não encontrado.");
    }

    /**
     * Barreira conservadora para sessoes das rotas legadas.
     * Ambos os fatos sem origem continuam na regra anterior, sem backfill.
     * Quando alguma proveniencia existe, grupo e sessao precisam ter a
     * mesma origem local comprovada. A FK de grupo nao transfere origem.
     */
    public boolean sessaoPertenceAoEscopo(SessaoGrupo sessao) {
        if (sessao == null || !grupoPertenceAoEscopo(sessao.getGrupo())) return false;
        String origemGrupo = sessao.getGrupo().getUnidadeOrigemChave();
        String origemSessao = sessao.getUnidadeOrigemChave();
        if (origemGrupo == null && origemSessao == null) return true;
        String local = ChaveUnidadeAssistencial.doUsuarioAtual(usuarioComUnidade());
        return Objects.equals(origemGrupo, local) && Objects.equals(origemSessao, local);
    }

    public void validarSessao(SessaoGrupo sessao) {
        if (!sessaoPertenceAoEscopo(sessao))
            throw new ResourceNotFoundException("Sessão não encontrada.");
    }

    /** Operações sobre a sessão inteira não podem alterar participantes de outra unidade. */
    public void validarSessaoCompleta(SessaoGrupo sessao) {
        validarSessao(sessao);
        sessao.getParticipantes().forEach(p -> validarAcesso(p.getPaciente()));
    }

    public UnidadePainelDTO unidadePainel() {
        Usuario usuario = usuarioComUnidade();
        return usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                ? new UnidadePainelDTO("USF:" + usuario.getUsfAtuacao().getCnes(), usuario.getUsfAtuacao().getNomeUsf())
                : new UnidadePainelDTO("CAPS:" + usuario.getUnidadeAtuacao().name(), usuario.getUnidadeAtuacao().name().replace('_', ' '));
    }
}
