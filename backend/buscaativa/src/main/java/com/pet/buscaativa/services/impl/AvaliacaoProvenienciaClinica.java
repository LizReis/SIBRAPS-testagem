package com.pet.buscaativa.services.impl;

import static com.pet.buscaativa.services.impl.AvaliacaoProvenienciaClinica.Situacao.COMPROVADA;
import static com.pet.buscaativa.services.impl.AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE;
import static com.pet.buscaativa.services.impl.AvaliacaoProvenienciaClinica.Situacao.SEM_ORIGEM_COMPROVADA;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.time.LocalDateTime;
import java.util.function.BiPredicate;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.SituacaoReserva;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;

final class AvaliacaoProvenienciaClinica {
    enum Situacao { COMPROVADA, SEM_ORIGEM_COMPROVADA, INCONSISTENTE }

    private AvaliacaoProvenienciaClinica() {}

    static boolean origemAusente(String origem) {
        return origem == null || origem.isBlank();
    }

    /**
     * Verificação estrutural da cadeia inteira: paciente, unidade, estado
     * canônico, cronologia e origem SUBSTITUIDA para cada sucessor.
     *
     * O callback é apenas uma CONSULTA read-only de irmãos já persistidos.
     * A validação de criação em AgendamentoServiceImpl permanece intacta.
     */
    static Situacao cadeiaAgendamento(Agendamento agendamento, Long pacienteId, String unidade,
            BiPredicate<Agendamento, Agendamento> existeOutroSucessor) {
        Objects.requireNonNull(existeOutroSucessor, "Consulta de sucessores obrigatoria.");
        var idsVisitados = new HashSet<Long>();
        var objetosVisitados = Collections.newSetFromMap(new IdentityHashMap<Agendamento, Boolean>());
        boolean semOrigem = false;
        boolean inconsistenciaEstrutural = false;
        for (var elo = agendamento; elo != null; elo = elo.getAgendamentoOriginal()) {
            if (!objetosVisitados.add(elo)
                    || elo.getId() != null && !idsVisitados.add(elo.getId())
                    || elo.getPaciente() == null || elo.getUsuario() == null
                    || !Objects.equals(pacienteId, elo.getPaciente().getId())) {
                return INCONSISTENTE;
            }

            String origem = elo.getUnidadeOrigemChave();
            if (origemAusente(origem)) {
                semOrigem = true;
            } else if (!Objects.equals(unidade, origem) || !elo.temEstadoCanonicoIdentificado()) {
                return INCONSISTENTE;
            }

            var original = elo.getAgendamentoOriginal();
            if (original == null) {
                if (elo.getSituacaoReserva() == SituacaoReserva.REMARCADA
                        || elo.isRemarcacaoAposFalta()) {
                    inconsistenciaEstrutural = true;
                }
            } else {
                // O próprio fluxo de criação exige que o antecessor seja
                // SUBSTITUIDA e que o sucessor tenha data/hora posterior.
                if (original.getSituacaoReserva() != SituacaoReserva.SUBSTITUIDA
                        || original.getDataAgendamento() == null || original.getHoraAtendimento() == null
                        || elo.getDataAgendamento() == null || elo.getHoraAtendimento() == null
                        || !LocalDateTime.of(original.getDataAgendamento(), original.getHoraAtendimento())
                            .isBefore(LocalDateTime.of(elo.getDataAgendamento(), elo.getHoraAtendimento()))
                        || existeOutroSucessor.test(original, elo)) {
                    inconsistenciaEstrutural = true;
                }
            }
        }
        // Ausência de prova histórica não deve ser "corrigida" por outra
        // inconsistência estrutural; em ambos os casos o corte é bloqueado.
        return semOrigem ? SEM_ORIGEM_COMPROVADA
                : inconsistenciaEstrutural ? INCONSISTENTE : COMPROVADA;
    }

    static Situacao origemSessao(SessaoGrupo sessao) {
        if (sessao.getGrupo() == null) return INCONSISTENTE;

        String origemSessao = sessao.getUnidadeOrigemChave();
        String origemGrupo = sessao.getGrupo().getUnidadeOrigemChave();
        if (origemAusente(origemSessao) || origemAusente(origemGrupo)) return SEM_ORIGEM_COMPROVADA;
        if (!origemSessao.equals(origemGrupo) || sessao.getStatus() == null) return INCONSISTENTE;

        if (sessao.getStatus() == StatusSessaoGrupo.REALIZADA
                && (!responsabilidadeRegistrada(sessao)
                    || sessao.getDataSessao() == null || sessao.getHorario() == null)) {
            return INCONSISTENTE;
        }
        return COMPROVADA;
    }

    static boolean acaoLocalSemFonteRegistrada(HistoricoPaciente historico) {
        // Tipos já emitidos em ações locais sem agendamento ou sessão vinculada.
        var tipo = historico.getTipo();
        return historico.getProfissional() != null && (tipo == TipoEventoHistoricoPaciente.BUSCA_ATIVA
                || tipo == TipoEventoHistoricoPaciente.SITUACAO_ATUALIZADA
                || tipo == TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO);
    }

    static boolean responsabilidadeRegistrada(SessaoGrupo sessao) {
        return sessao.getResponsavelAtendimento() != null
                && !sessao.isResponsabilidadePendente()
                && sessao.getMotivoResponsabilidadePendente() == null
                && ("CONFIRMACAO_REALIZACAO".equals(sessao.getOrigemResponsabilidade())
                    || "ENVERS_TRANSICAO_REALIZADA".equals(sessao.getOrigemResponsabilidade()));
    }

    static boolean frequenciaIndividualEmAnalise(Agendamento agenda) {
        return !agenda.temEstadoCanonicoIdentificado()
                || agenda.getFrequenciaAssistencial() != FrequenciaAssistencial.NAO_REGISTRADA;
    }

    static boolean frequenciaIndividualRegistrada(Agendamento agenda) {
        var frequencia = agenda.getFrequenciaAssistencial();
        return (frequencia == FrequenciaAssistencial.PRESENTE || frequencia == FrequenciaAssistencial.FALTOU)
                && agenda.getDataAgendamento() != null && agenda.getHoraAtendimento() != null;
    }

    static boolean frequenciaRegistrada(StatusPresencaGrupo frequencia) {
        return frequencia == StatusPresencaGrupo.PRESENTE || frequencia == StatusPresencaGrupo.FALTOU;
    }
}
