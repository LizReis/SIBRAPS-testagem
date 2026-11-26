package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia;
import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia.Decisao;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.DecisaoRevisaoProvenienciaComandoRepository;
import com.pet.buscaativa.repositories.FilaPendenciasRevisaoProvenienciaRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.PoliticaJustificativaCorrecao;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * Comando INTERNO de registro da decisao humana V13. Sem controller/rota.
 *
 * O administrador deve agir expressamente informando paciente, ticket,
 * decisao e justificativa. Esta camada confere o vinculo local, a unidade
 * autentica, a identidade do ticket e a proveniencia do fato original
 * ANTES da gravacao. Nao infere unidade a partir de referencia territorial.
 *
 * A decisao e um evento append-only auditado pelo Envers; nem a pendencia
 * original nem o fato clinico sao alterados, mesmo em caso de APROVAR.
 */
@Service
@RequiredArgsConstructor
public class RegistroDecisaoRevisaoProvenienciaService {
    private final EscopoUnidadeService escopo;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final VerificacaoFatoRevisaoProvenienciaService verificador;
    private final PoliticaJustificativaCorrecao justificativas;
    private final VinculoAssistencialRepository vinculos;
    private final FilaPendenciasRevisaoProvenienciaRepository fila;
    private final DecisaoRevisaoProvenienciaComandoRepository comandos;
    private final Clock clock;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    @Transactional
    public UUID registrar(UUID pacienteIdPublico, UUID pendenciaId,
            Decisao decisao, String justificativaInformada) {
        var revisor = escopo.usuarioLogado();
        if (revisor == null || revisor.getTipoUsuario() != TipoUsuario.ADMINISTRADOR)
            throw new AccessDeniedException("Perfil sem permissao para decidir pendencias.");
        escopo.validarUnidadeConfigurada();
        String unidade = ChaveUnidadeAssistencial.doUsuarioAtual(revisor);
        if (pacienteIdPublico == null || pendenciaId == null)
            throw naoEncontrada();
        if (decisao == null)
            throw new IllegalArgumentException("Decisao obrigatoria.");

        // Evita escrita com unidade manipulada pelo cliente; o vinculo de
        // identidade global nao fornece direitos a outra unidade.
        VinculoAssistencial vinculo = autorizacao.exigirVinculoLocal(pacienteIdPublico);
        if (vinculo == null || vinculo.getId() == null || vinculo.getPaciente() == null
                || !Objects.equals(unidade, vinculo.getUnidadeChave()))
            throw naoEncontrada();

        // Serializa revisoes simultaneas do mesmo paciente/unidade. O banco
        // ainda possui UNIQUE(pendencia_id) como segunda barreira.
        VinculoAssistencial bloqueado = vinculos.findByIdForPendenciaUpdate(vinculo.getId())
                .orElseThrow(RegistroDecisaoRevisaoProvenienciaService::naoEncontrada);
        if (bloqueado.getPaciente() == null
                || !Objects.equals(bloqueado.getPaciente().getId(), vinculo.getPaciente().getId())
                || !Objects.equals(bloqueado.getUnidadeChave(), unidade))
            throw naoEncontrada();

        var pendencia = fila.findByIdAndUnidadeChaveAndEstado(
                pendenciaId, unidade, Estado.PENDENTE)
                .orElseThrow(RegistroDecisaoRevisaoProvenienciaService::naoEncontrada);
        if (!Objects.equals(pendencia.getId(), pendenciaId)
                || !Objects.equals(pendencia.getPacienteId(), bloqueado.getPaciente().getId())
                || !Objects.equals(pendencia.getUnidadeChave(), unidade)
                || pendencia.getVinculo() == null
                || !Objects.equals(pendencia.getVinculo().getId(), bloqueado.getId()))
            throw naoEncontrada();

        // A existencia do ticket nao comprova o fato clinico: revalidar os
        // dados originais ANTES de registrar a decisao administrativa.
        var fato = verificador.verificar(
                pacienteIdPublico, pendencia.getTipoRegistro(), pendencia.getRegistroId());
        boolean revisaoOrigem=pendencia.getSituacaoEvidencia()==
                com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Situacao.REVISAR_ORIGEM_DESCONHECIDA;
        if (revisaoOrigem!=fato.historicoSemOrigemComFonteLocal()
                || !Objects.equals(fato.pacienteId(), pendencia.getPacienteId())
                || !Objects.equals(fato.unidadeChave(), unidade)
                || fato.tipoRegistro() != pendencia.getTipoRegistro()
                || !Objects.equals(fato.registroId(), pendencia.getRegistroId()))
            throw naoEncontrada();

        String motivo = justificativas.validar(justificativaInformada);
        if (comandos.existsByPendenciaIdAndPacienteIdAndUnidadeChave(
                pendenciaId, pendencia.getPacienteId(), unidade))
            throw new ValidationException("Ja existe uma decisao para esta pendencia.");

        var evento = DecisaoRevisaoProveniencia.registrarRevisaoHumana(
                pendencia, decisao, motivo, revisor, Instant.now(clock));
        if(revisaoOrigem && decisao==Decisao.APROVAR) {
            var prova=fato.provaOrigem();
            boolean instalada=((Number)em.createNativeQuery("""
                    select count(*) from information_schema.tables where table_schema=current_schema()
                    and table_name='TB_PROVA_APROVACAO_ORIGEM'
                    """).getSingleResult()).longValue()==1;
            if(!instalada || prova==null)
                throw new com.pet.buscaativa.services.exceptions.ConflictException("Estrutura de prova de origem não instalada ou evidência ausente.");
            comandos.save(evento);
            // A FK exige a decisão V13 já persistida; o flush não confirma a transação.
            em.flush();
            var c=prova.candidato();
            em.createNativeQuery("""
                    insert into tb_prova_aprovacao_origem
                    (decisao_id,pendencia_id,vinculo_id,paciente_id,unidade_chave,tipo_registro,historico_id,
                     situacao_evidencia,decisao,fonte,fonte_id,versao_fonte,participante_id,responsavel_id,
                     ocorrido_em,frequencia,tipo_historico)
                    values (:decisao,:ticket,:vinculo,:paciente,:unidade,'HISTORICO',:historico,
                     'REVISAR_ORIGEM_DESCONHECIDA','APROVAR',:fonte,:fonteId,:versao,:participante,
                     :responsavel,:horario,:frequencia,:tipo)
                    """).setParameter("decisao",evento.getId()).setParameter("ticket",pendenciaId)
                    .setParameter("vinculo",bloqueado.getId()).setParameter("paciente",fato.pacienteId())
                    .setParameter("unidade",unidade).setParameter("historico",c.historico())
                    .setParameter("fonte",c.fonte().name()).setParameter("fonteId",c.registroFonte())
                    .setParameter("versao",prova.versaoFonte())
                    .setParameter("participante",prova.participanteId())
                    .setParameter("responsavel",prova.responsavelId()).setParameter("horario",c.ocorridoEm())
                    .setParameter("frequencia",c.frequencia().name()).setParameter("tipo",prova.tipoHistorico().name())
                    .executeUpdate();
        } else comandos.save(evento);
        return evento.getId();
    }

    private static ResourceNotFoundException naoEncontrada() {
        return new ResourceNotFoundException("Pendencia nao encontrada.");
    }
}
