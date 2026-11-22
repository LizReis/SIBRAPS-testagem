package com.pet.buscaativa.migration;

import java.util.Objects;

/**
 * Triagem PURA de proveniencia para elaborar um plano de conciliacao do
 * cadastro unico. Sem acesso ao banco, endpoint, logs, CPF/CNS ou escrita.
 *
 * A origem "compativel" e apenas um INDICIO tecnico para conferencia humana,
 * NUNCA autorizacao de backfill, acesso ao fato ou atribuicao automatica.
 * O chamador interno deve ter exigido vinculo da unidade autenticada.
 */
public final class ClassificadorEvidenciaClinicaLocal {
    private ClassificadorEvidenciaClinicaLocal() {}

    public enum Fonte { MANUAL, AGENDAMENTO, SESSAO_GRUPO }

    public enum Situacao {
        FORA_DA_UNIDADE,
        REVISAR_ORIGEM_DESCONHECIDA,
        REVISAR_FONTE_SEM_ORIGEM,
        REVISAR_ORIGENS_CONFLITANTES,
        REVISAR_INTEGRIDADE_DA_FONTE,
        COMPATIVEL_SOMENTE_PARA_CONFERENCIA
    }

    /**
     * Metadados clinicos minimos ja lidos pelo processo autenticado.
     * Nao incluir identificadores, conteudo clinico nem dados cadastrais.
     *
     * integridadePaciente indica que a fonte individual pertence a pessoa,
     * ou que ha participacao comprovada na sessao/grupo de origem.
     * Se tipo MANUAL, nao existe fato fonte e o campo deve ser true.
     */
    public record Evidencia(
            Fonte fonte,
            String origemDoEvento,
            String origemDaFonte,
            boolean integridadePaciente,
            boolean fonteEncontrada) {}

    /**
     * A ordem de verificacao evita expor razoes detalhadas sobre registros
     * de OUTRA unidade. Metadados de fato com origem divergente sao somente
     * classificados, nunca mostrados a usuarios de outras unidades.
     */
    public static Situacao classificar(String chaveUnidadeAutorizada, Evidencia evidencia) {
        if (chaveUnidadeAutorizada == null || chaveUnidadeAutorizada.isBlank())
            throw new IllegalArgumentException("Unidade autenticada obrigatoria.");
        Objects.requireNonNull(evidencia, "Evidencia obrigatoria.");

        if (evidencia.origemDoEvento() == null || evidencia.origemDoEvento().isBlank())
            return Situacao.REVISAR_ORIGEM_DESCONHECIDA;

        if (!chaveUnidadeAutorizada.equals(evidencia.origemDoEvento()))
            return Situacao.FORA_DA_UNIDADE;

        if (evidencia.fonte() == null || !evidencia.integridadePaciente())
            return Situacao.REVISAR_INTEGRIDADE_DA_FONTE;

        if (evidencia.fonte() == Fonte.MANUAL) {
            // A acao manual nao deve apresentar outra fonte clinica.
            if (evidencia.fonteEncontrada() || evidencia.origemDaFonte() != null)
                return Situacao.REVISAR_INTEGRIDADE_DA_FONTE;
            return Situacao.COMPATIVEL_SOMENTE_PARA_CONFERENCIA;
        }

        if (!evidencia.fonteEncontrada())
            return Situacao.REVISAR_INTEGRIDADE_DA_FONTE;

        if (evidencia.origemDaFonte() == null || evidencia.origemDaFonte().isBlank())
            return Situacao.REVISAR_FONTE_SEM_ORIGEM;

        if (!chaveUnidadeAutorizada.equals(evidencia.origemDaFonte()))
            return Situacao.REVISAR_ORIGENS_CONFLITANTES;

        return Situacao.COMPATIVEL_SOMENTE_PARA_CONFERENCIA;
    }
}
