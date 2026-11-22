package com.pet.buscaativa.services.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * INVENTARIO de evidencias para a futura separacao dos indicadores clinicos.
 *
 * Nao calcula faltas CONSECUTIVAS, risco, ultima presenca, visita domiciliar
 * ou busca ativa; portanto, nao deve alimentar painéis nem APIs assistenciais.
 * Registros legados sem proveniencia nao sao atribuidos automaticamente.
 * Nenhum metodo modifica o banco.
 */
@Service
@RequiredArgsConstructor
public class InventarioFrequenciasVinculoService {
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final EntityManager em;

    public record Inventario(
            long presencasIndividuais,
            long faltasIndividuais,
            long presencasEmGrupo,
            long faltasEmGrupo,
            long agendamentosLocaisPendentesDeEvidencia
    ) {
        /** Quantidades isoladas nao substituem cronologia nem classificacao de risco. */
        public long totalPresencasComprovadas() { return presencasIndividuais + presencasEmGrupo; }
        public long totalFaltasComprovadas() { return faltasIndividuais + faltasEmGrupo; }
    }

    @Transactional(readOnly = true)
    public Inventario inventariar(UUID pacienteId) {
        // A chave da unidade e obtida do vinculo explicitamente autorizado;
        // jamais de parametro recebido do cliente ou de referencia territorial.
        String unidade = autorizacao.exigirVinculoLocal(pacienteId).getUnidadeChave();
        return new Inventario(
                contarIndividuais(pacienteId, unidade, FrequenciaAssistencial.PRESENTE),
                contarIndividuais(pacienteId, unidade, FrequenciaAssistencial.FALTOU),
                contarGrupos(pacienteId, unidade, StatusPresencaGrupo.PRESENTE),
                contarGrupos(pacienteId, unidade, StatusPresencaGrupo.FALTOU),
                contarIndividuaisPendentes(pacienteId, unidade));
    }

    private long contarIndividuais(UUID paciente, String unidade, FrequenciaAssistencial frequencia) {
        return em.createQuery("""
                select count(a) from Agendamento a
                where a.paciente.idPublico = :paciente
                  and a.unidadeOrigemChave = :unidade
                  and a.frequenciaAssistencial = :frequencia
                  and a.migracaoPendente = false
                  and a.dataAgendamento is not null
                  and a.horaAtendimento is not null
                """, Long.class)
                .setParameter("paciente", paciente)
                .setParameter("unidade", unidade)
                .setParameter("frequencia", frequencia)
                .getSingleResult();
    }

    private long contarGrupos(UUID paciente, String unidade, StatusPresencaGrupo frequencia) {
        return em.createQuery("""
                select count(p) from SessaoGrupoParticipante p
                join p.sessaoGrupo s
                join s.grupo g
                where p.paciente.idPublico = :paciente
                  and s.unidadeOrigemChave = :unidade
                  and g.unidadeOrigemChave = :unidade
                  and s.status = :realizada
                  and p.statusPresenca = :frequencia
                  and s.dataSessao is not null
                  and s.horario is not null
                """, Long.class)
                .setParameter("paciente", paciente)
                .setParameter("unidade", unidade)
                .setParameter("realizada", StatusSessaoGrupo.REALIZADA)
                .setParameter("frequencia", frequencia)
                .getSingleResult();
    }

    private long contarIndividuaisPendentes(UUID paciente, String unidade) {
        return em.createQuery("""
                select count(a) from Agendamento a
                where a.paciente.idPublico = :paciente
                  and a.unidadeOrigemChave = :unidade
                  and (a.migracaoPendente = true
                       or (a.frequenciaAssistencial in :comprovadas
                           and (a.dataAgendamento is null or a.horaAtendimento is null)))
                """, Long.class)
                .setParameter("paciente", paciente)
                .setParameter("unidade", unidade)
                .setParameter("comprovadas", java.util.List.of(
                        FrequenciaAssistencial.PRESENTE, FrequenciaAssistencial.FALTOU))
                .getSingleResult();
    }
}
