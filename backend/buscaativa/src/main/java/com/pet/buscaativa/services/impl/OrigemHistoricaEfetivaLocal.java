package com.pet.buscaativa.services.impl;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.migration.AplicacaoOrigemSchemaGuard;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.VerificacaoFatoRevisaoProvenienciaService.CandidatoOrigem;
import com.pet.buscaativa.services.impl.VerificacaoFatoRevisaoProvenienciaService.ProvaOrigem;
import lombok.RequiredArgsConstructor;

/** Leitura restrita a um vínculo local já autorizado, na transação da consulta.
 * Nunca alterar a origem bruta nem criar um novo fato de frequência. */
@Component
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
class OrigemHistoricaEfetivaLocal {
    private final JdbcTemplate jdbc;
    private final HistoricoPacienteRepository historicos;
    private final VerificacaoFatoRevisaoProvenienciaService verificador;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final EscopoUnidadeService escopo;

    record Reconhecimento(UUID id,UUID ticket,Long historico,boolean comprovada,UUID reversao) {}
    private record Reversao(UUID id,OffsetDateTime registradaEm) {}
    private record Aplicacao(UUID id,UUID ticket,ProvaOrigem prova,OffsetDateTime aplicadaEm,OffsetDateTime decididaEm) {}

    private record Correcao(int anterior,int resultante,FrequenciaAssistencial antes,
            FrequenciaAssistencial depois,OffsetDateTime ocorridoEm) {}

    /** A aprovação prova a origem; a auditoria existente pode provar uma
     * mudança posterior de frequência do MESMO fato. Nunca aceitar mudança
     * sem trilha, nem alterar a prova original ou a regra da aplicação V28. */
    private boolean continuidadeComprovada(VinculoAssistencial local,Aplicacao aplicacao,ProvaOrigem atual) {
        var inicial=aplicacao.prova();
        if(inicial.equals(atual)) return true;
        var a=inicial.candidato();var b=atual.candidato();
        if(!java.util.Objects.equals(a.historico(),b.historico()) || a.fonte()!=b.fonte()
                || !java.util.Objects.equals(a.registroFonte(),b.registroFonte())
                || !java.util.Objects.equals(a.ocorridoEm(),b.ocorridoEm())
                || !java.util.Objects.equals(inicial.participanteId(),atual.participanteId())
                || !java.util.Objects.equals(inicial.responsavelId(),atual.responsavelId())
                || inicial.versaoFonte()==null || atual.versaoFonte()==null
                || atual.versaoFonte()<=inicial.versaoFonte()) return false;
        boolean grupo=a.fonte()==TipoRegistro.SESSAO_GRUPO;
        String filtro=grupo?"tipo_correcao='GRUPO' and sessao_id=? and participante_id=? and agendamento_id is null"
                :"tipo_correcao='INDIVIDUAL' and agendamento_id=? and sessao_id is null and participante_id is null";
        var argumentos=new java.util.ArrayList<Object>();
        // O escritor de frequência registra a chave de auditoria do painel
        // (USF:CNES / CAPS:enum), não a chave canônica do vínculo.
        // Conferir o contexto autenticado e a fonte já comprovada; nunca
        // deduzir a origem de um fato antigo a partir desse rótulo.
        var usuario=escopo.usuarioLogado();
        if(!local.getUnidadeChave().equals(com.pet.buscaativa.entities.ChaveUnidadeAssistencial.doUsuarioAtual(usuario)))
            return false;
        if(usuario.getUnidadeAtuacao()==com.pet.buscaativa.entities.enums.UnidadeAtuacao.USF) {
            var usf=usuario.getUsfAtuacao();
            if(usf.getCnes()==null || usf.getCnes().isBlank()
                    || !java.util.Objects.equals(jdbc.queryForObject(
                        "select count(*) from tb_usf_referencia where cnes=?",Long.class,usf.getCnes()),1L))
                return false;
        }
        argumentos.add(local.getPaciente().getId());argumentos.add(escopo.unidadePainel().valor());
        argumentos.add(inicial.responsavelId());argumentos.add(a.registroFonte());
        if(grupo) argumentos.add(inicial.participanteId());
        argumentos.add(inicial.versaoFonte());argumentos.add(atual.versaoFonte());
        var correcoes=jdbc.query("""
                select versao_anterior,versao_resultante,frequencia_anterior,frequencia_nova,ocorrido_em
                from tb_auditoria_correcao_frequencia
                where paciente_id=? and unidade=? and responsavel_id=?
                  and (perfil='ADMINISTRADOR' or (perfil='PROFISSIONAL' and ator_id=responsavel_id))
                  and
                """+filtro+" and versao_anterior>=? and versao_anterior<? order by versao_anterior,id",
                (rs,n)->new Correcao(rs.getInt(1),rs.getInt(2),FrequenciaAssistencial.valueOf(rs.getString(3)),
                        FrequenciaAssistencial.valueOf(rs.getString(4)),rs.getObject(5,OffsetDateTime.class)),
                argumentos.toArray());
        int versao=inicial.versaoFonte();
        var frequencia=a.frequencia();
        var instante=aplicacao.aplicadaEm();
        for(var c:correcoes) {
            if(c.anterior()!=versao || c.resultante()!=versao+1 || c.antes()!=frequencia
                    || c.depois()==c.antes() || c.ocorridoEm().isBefore(instante)) return false;
            versao=c.resultante();frequencia=c.depois();instante=c.ocorridoEm();
        }
        return versao==atual.versaoFonte() && frequencia==b.frequencia();
    }

    boolean reconhecida(VinculoAssistencial local,Long historico) {
        if(historico==null || org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()==null)
            return false;
        var r=consultar(local).get(historico);
        return r!=null && r.comprovada();
    }

    Map<Long,Reconhecimento> consultar(VinculoAssistencial local) {
        var resultado=new LinkedHashMap<Long,Reconhecimento>();
        for(var a:consultarPorTicket(local).values()) {
            var anterior=resultado.get(a.historico());
            if(anterior==null || (anterior.reversao()!=null && a.reversao()==null))
                resultado.put(a.historico(),a);
        }
        return Map.copyOf(resultado);
    }

    java.util.Set<Long> inconsistentes(VinculoAssistencial local) {
        return com.pet.buscaativa.migration.EvidenciaOrigemHistorica.inconsistentes(jdbc,
                local.getId(),local.getPaciente().getId(),local.getUnidadeChave());
    }

    Map<UUID,Reconhecimento> consultarPorTicket(VinculoAssistencial local) {
        var autorizado=autorizacao.exigirVinculoIdentificado(local.getPaciente().getIdPublico(),local.getIdPublico());
        if(!java.util.Objects.equals(autorizado.getId(),local.getId())
                || !java.util.Objects.equals(autorizado.getUnidadeChave(),local.getUnidadeChave()))
            throw new ResourceNotFoundException("Paciente não encontrado.");
        if(!AplicacaoOrigemSchemaGuard.instalada(jdbc)) return Map.of();
        var registros=jdbc.query("""
                select a.id,a.pendencia_id,a.historico_id,p.fonte,p.fonte_id,p.versao_fonte,p.participante_id,
                       p.responsavel_id,p.ocorrido_em,p.frequencia,p.tipo_historico,a.registrado_em,d.decidida_em
                from tb_aplicacao_origem_historica a
                join tb_prova_aprovacao_origem p on p.decisao_id=a.decisao_id and p.pendencia_id=a.pendencia_id
                    and p.vinculo_id=a.vinculo_id and p.paciente_id=a.paciente_id
                    and p.unidade_chave=a.unidade_chave and p.historico_id=a.historico_id
                join tb_decisao_revisao_proveniencia d on d.id=p.decisao_id and d.pendencia_id=p.pendencia_id
                    and d.paciente_id=p.paciente_id and d.unidade_chave=p.unidade_chave and d.decisao='APROVAR'
                where a.vinculo_id=? and a.paciente_id=? and a.unidade_chave=?
                order by a.registrado_em,a.id
                """,(rs,n)->new Aplicacao(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),new ProvaOrigem(
                        new CandidatoOrigem(rs.getLong(3),TipoRegistro.valueOf(rs.getString(4)),rs.getLong(5),
                            rs.getObject(9,LocalDateTime.class),FrequenciaAssistencial.valueOf(rs.getString(10))),
                        rs.getInt(6),rs.getObject(7,Long.class),rs.getLong(8),
                        TipoEventoHistoricoPaciente.valueOf(rs.getString(11))),rs.getObject(12,OffsetDateTime.class),
                        rs.getObject(13,OffsetDateTime.class)),local.getId(),local.getPaciente().getId(),local.getUnidadeChave());
        var reversoes=new LinkedHashMap<UUID,Reversao>();
        if(com.pet.buscaativa.migration.ReversaoOrigemSchemaGuard.instalada(jdbc)) {
            jdbc.query("""
                    select aplicacao_id,id,registrado_em from tb_reversao_origem_historica
                    where vinculo_id=? and paciente_id=? and unidade_chave=?
                    """,rs->{ reversoes.put(rs.getObject(1,UUID.class),
                            new Reversao(rs.getObject(2,UUID.class),rs.getObject(3,OffsetDateTime.class))); },
                    local.getId(),local.getPaciente().getId(),local.getUnidadeChave());
        }
        var invalidos=inconsistentes(local);
        var resultado=new LinkedHashMap<UUID,Reconhecimento>();
        var ativos=new java.util.HashMap<Long,Integer>();
        for(var a:registros) {
            long id=a.prova().candidato().historico();
            if(invalidos.contains(id)) {
                resultado.put(a.ticket(),new Reconhecimento(a.id(),a.ticket(),id,false,null));
                continue;
            }
            var reversao=reversoes.get(a.id());
            if(reversao!=null) {
                // Reversão incompatível também impede o reconhecimento;
                // só afirmar REVERTIDA quando a cronologia for coerente.
                resultado.put(a.ticket(),new Reconhecimento(a.id(),a.ticket(),id,false,
                        reversao.registradaEm().isBefore(a.aplicadaEm())?null:reversao.id()));
                continue;
            }
            boolean comprovada=false;
            try {
                var h=historicos.findById(id).orElseThrow(()->new ResourceNotFoundException("Histórico não encontrado."));
                comprovada=!a.aplicadaEm().isBefore(a.decididaEm())
                        && continuidadeComprovada(local,a,verificador.conferirHistoricoSemOrigem(h,local));
            } catch(ResourceNotFoundException e) {
                // Divergência posterior não autoriza leitura nem apaga a aplicação.
            }
            resultado.put(a.ticket(),new Reconhecimento(a.id(),a.ticket(),id,comprovada,null));
        }
        for(var a:resultado.values()) if(a.reversao()==null) ativos.merge(a.historico(),1,Integer::sum);
        // Dados incompatíveis não podem produzir reconhecimento arbitrário.
        resultado.replaceAll((ticket,a)->a.reversao()==null && ativos.getOrDefault(a.historico(),0)>1
                ?new Reconhecimento(a.id(),a.ticket(),a.historico(),false,null):a);
        return Map.copyOf(resultado);
    }
}
