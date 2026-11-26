package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.services.exceptions.ConflictException;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class DesignacaoSessaoService {
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final Clock clock;

    boolean aindaNaoIniciou(SessaoGrupo sessao, LocalDateTime agora) {
        return LocalDateTime.of(sessao.getDataSessao(), sessao.getHorario()).isAfter(agora);
    }

    /** Chamado pelos escritores com os bloqueios da série adquiridos, antes da troca do grupo. */
    void transferir(SessaoGrupo sessao, Usuario novo, LocalDateTime agora) {
        if (sessao.getStatus() != StatusSessaoGrupo.AGENDADA || sessao.isResponsabilidadePendente()) return;
        if (aindaNaoIniciou(sessao, agora)) {
            sessao.designarProfissional(novo);
            return;
        }
        if (sessao.getProfissionalDesignado() == null) {
            var revisoes = revisoesDaCoordenacao(sessao);
            var inicio = inicio(sessao);
            if (inicio != null && programacaoAuditadaAoInicio(sessao, inicio)
                    && revisoes.stream().noneMatch(r -> r.instante() == inicio)) {
                revisoes.stream().filter(r -> r.instante() < inicio).reduce((a, b) -> b)
                        .map(Revisao::profissional).map(id -> em.find(Usuario.class, id))
                        .ifPresent(sessao::designarProfissional);
            }
        }
        sessao.sinalizarRevisaoDeAutoria();
    }

    void exigirSemRevisao(SessaoGrupo sessao) {
        if (sessao.isResponsabilidadePendente())
            throw new ConflictException("Autoria da sessão pendente de revisão administrativa na unidade.");
    }

    /** A confirmação explícita segue o fluxo habitual somente quando a designação é verificável. */
    Usuario paraConfirmacao(SessaoGrupo sessao) {
        exigirSemRevisao(sessao);
        var coordenador = sessao.getGrupo().getCoordenador();
        var designado = sessao.getProfissionalDesignado();
        if (designado != null && designado.getId().equals(coordenador.getId())) return designado;
        if (designado == null) {
            var revisoes = revisoesDaCoordenacao(sessao);
            var inicio = inicio(sessao);
            if (!revisoes.isEmpty() && inicio != null
                    && revisoes.getLast().profissional().equals(coordenador.getId())) {
                // Uma troca no início ou depois dele torna a confirmação ordinária insegura.
                boolean trocaTardia = false;
                var anterior = revisoes.getFirst();
                for (var revisao : revisoes) {
                    if (revisao.instante() >= inicio && !revisao.profissional().equals(anterior.profissional())) {
                        trocaTardia = true;
                        break;
                    }
                    anterior = revisao;
                }
                if (!trocaTardia) return coordenador;
            }
        }
        throw new ConflictException("Designação histórica inconclusiva. Encaminhe a sessão à administração da unidade.");
    }

    private Long inicio(SessaoGrupo sessao) {
        var local = LocalDateTime.of(sessao.getDataSessao(), sessao.getHorario());
        var offsets = clock.getZone().getRules().getValidOffsets(local);
        return offsets.size() == 1 ? local.toInstant(offsets.getFirst()).toEpochMilli() : null;
    }

    private boolean programacaoAuditadaAoInicio(SessaoGrupo sessao, long inicio) {
        return jdbc.query("""
                select a.grupo_id, a.data_sessao, a.horario, a.status, a.revtype, a.unidade_origem_chave
                from tb_sessao_grupo_aud a join revinfo r on r.rev = a.rev
                where a.id = ? and r.revtstmp < ? order by a.rev desc fetch first row only
                """, rs -> rs.next() && rs.getInt("revtype") != 2
                        && sessao.getGrupo().getId().equals(rs.getObject("grupo_id", Long.class))
                        && Objects.equals(sessao.getUnidadeOrigemChave(), rs.getString("unidade_origem_chave"))
                        && sessao.getDataSessao().equals(rs.getObject("data_sessao", LocalDate.class))
                        && sessao.getHorario().equals(rs.getObject("horario", LocalTime.class))
                        && StatusSessaoGrupo.AGENDADA.name().equals(rs.getString("status")), sessao.getId(), inicio);
    }

    private record Revisao(Long profissional, long instante, int tipo, String unidade) {}

    private List<Revisao> revisoesDaCoordenacao(SessaoGrupo sessao) {
        var revisoes = jdbc.query("""
                select a.coordenador_id, r.revtstmp, a.revtype, a.unidade_origem_chave
                from tb_grupo_terapeutico_aud a join revinfo r on r.rev = a.rev
                where a.id = ? order by a.rev
                """, (rs, n) -> new Revisao(rs.getObject(1, Long.class), rs.getLong(2), rs.getInt(3), rs.getString(4)),
                sessao.getGrupo().getId());
        // Uma lacuna na trilha impede usá-la como evidência.
        if (revisoes.isEmpty() || revisoes.getFirst().tipo() != 0
                || revisoes.stream().anyMatch(r -> r.profissional() == null || r.tipo() == 2
                        || !Objects.equals(r.unidade(), sessao.getGrupo().getUnidadeOrigemChave()))) return List.of();
        return revisoes;
    }
}
