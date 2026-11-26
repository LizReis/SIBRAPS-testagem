package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pet.buscaativa.migration.ReversaoResolucaoSchemaGuard;
import com.pet.buscaativa.services.PoliticaJustificativaCorrecao;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.PlanoResolucaoFrequencia.Fato;
import com.pet.buscaativa.services.impl.PlanoResolucaoFrequencia.Referencia;
import lombok.RequiredArgsConstructor;

/**
 * Componente interno, ainda sem rota ou chamador operacional.
 * A etapa 6.4C deve consumir a resolução e recalcular todos os indicadores
 * na mesma transação antes de permitir sua aplicação real.
 */
@Component
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
class PreparacaoResolucaoFrequenciaLocal {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final LinhaDoTempoFrequenciasVinculoService linha;
    private final PoliticaJustificativaCorrecao justificativas;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    UUID registrar(UUID paciente, UUID vinculo, Integer versaoVinculo, Referencia valido,
            Referencia desconsiderado, String justificativa) {
        var contexto=atores.autorizar(false);
        if(!ReversaoResolucaoSchemaGuard.instalada(jdbc))
            throw new ConflictException("Estrutura de resolução não instalada.");
        var bloqueados=coordenacao.paraVinculo(contexto,paciente,vinculo,null);
        var local=bloqueados.vinculos().get(paciente);
        var ator=bloqueados.atores().ator();
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versaoVinculo,local.getVersion());
        String motivo=justificativas.validar(justificativa);
        var evidencias=linha.examinar(local,true);
        var a=carregar(evidencias,valido);
        var b=carregar(evidencias,desconsiderado);
        PlanoResolucaoFrequencia.validar(valido,desconsiderado,a,b,ator.getId(),ator.getTipoUsuario());
        if(jaDesconsiderado(valido) || jaDesconsiderado(desconsiderado) || possuiDependentes(desconsiderado))
            throw new ConflictException("Os fatos já participam de resolução incompatível.");
        var id=UUID.randomUUID();
        jdbc.update("""
                insert into tb_resolucao_frequencia_local
                (id,vinculo_id,paciente_id,unidade_chave,fonte_valida,fato_valido,versao_valida,frequencia_valida,responsavel_valido,
                fonte_desconsiderada,fato_desconsiderado,versao_desconsiderada,frequencia_desconsiderada,responsavel_desconsiderado,
                horario,ator_id,perfil,justificativa,registrado_em)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,local.getId(),local.getPaciente().getId(),local.getUnidadeChave(),
                valido.fonte().name(),valido.id(),valido.versao(),frequencia(a),a.responsavel(),
                desconsiderado.fonte().name(),desconsiderado.id(),desconsiderado.versao(),frequencia(b),b.responsavel(),
                a.horario(),ator.getId(),ator.getTipoUsuario().name(),motivo,
                OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC));
        return id;
    }

    private record ResolucaoAtiva(Long responsavelValido,Long responsavelDesconsiderado) {}

    UUID desfazer(UUID paciente, UUID vinculo, Integer versaoVinculo, UUID resolucao, String justificativa) {
        var contexto=atores.autorizar(false);
        if(!ReversaoResolucaoSchemaGuard.instalada(jdbc))
            throw new ConflictException("Estrutura de reversão não instalada.");
        var bloqueados=coordenacao.paraVinculo(contexto,paciente,vinculo,null);
        var local=bloqueados.vinculos().get(paciente);
        var ator=bloqueados.atores().ator();
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versaoVinculo,local.getVersion());
        if(resolucao==null) throw naoEncontrado();
        var original=jdbc.query("""
                select responsavel_valido,responsavel_desconsiderado from tb_resolucao_frequencia_local
                where id=? and vinculo_id=? and paciente_id=? and unidade_chave=?
                """,(rs,n)->new ResolucaoAtiva(rs.getLong(1),rs.getLong(2)),resolucao,local.getId(),
                local.getPaciente().getId(),local.getUnidadeChave()).stream().findFirst()
                .orElseThrow(PreparacaoResolucaoFrequenciaLocal::naoEncontrado);
        PlanoResolucaoFrequencia.validarResponsabilidade(original.responsavelValido(),
                original.responsavelDesconsiderado(),ator.getId(),ator.getTipoUsuario());
        String motivo=justificativas.validar(justificativa);
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tb_reversao_resolucao_frequencia where resolucao_id=?)
                """,Boolean.class,resolucao)))
            throw new ConflictException("Esta resolução já foi desfeita.");
        var id=UUID.randomUUID();
        jdbc.update("""
                insert into tb_reversao_resolucao_frequencia
                (id,resolucao_id,vinculo_id,paciente_id,unidade_chave,responsavel_valido,
                responsavel_desconsiderado,ator_id,perfil,justificativa,registrado_em)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """,id,resolucao,local.getId(),local.getPaciente().getId(),local.getUnidadeChave(),
                original.responsavelValido(),original.responsavelDesconsiderado(),ator.getId(),
                ator.getTipoUsuario().name(),motivo,
                OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC));
        return id;
    }

    private static Fato carregar(LinhaDoTempoFrequenciasVinculoService.Evidencias e, Referencia ref) {
        if(ref==null || ref.fonte()==null || ref.id()==null) throw naoEncontrado();
        var evento=e.diagnostico().eventosComOrigemLocal().stream()
                .filter(f->f.fonte()==ref.fonte() && ref.id().equals(f.registroId()))
                .findFirst().orElseThrow(PreparacaoResolucaoFrequenciaLocal::naoEncontrado);
        return switch(ref.fonte()) {
            case ATENDIMENTO_INDIVIDUAL -> {
                var a=e.agenda().stream().filter(f->ref.id().equals(f.getId()))
                        .findFirst().orElseThrow(PreparacaoResolucaoFrequenciaLocal::naoEncontrado);
                yield new Fato(new Referencia(ref.fonte(),a.getId(),a.getVersion()),
                        a.getUsuario().getId(),evento.ocorridoEm(),evento.presente());
            }
            case SESSAO_GRUPO -> {
                var p=e.participacoes().stream().filter(f->ref.id().equals(f.getId()))
                        .findFirst().orElseThrow(PreparacaoResolucaoFrequenciaLocal::naoEncontrado);
                var s=p.getSessaoGrupo();
                yield new Fato(new Referencia(ref.fonte(),p.getId(),s.getVersion()),
                        s.getResponsavelAtendimento().getId(),evento.ocorridoEm(),evento.presente());
            }
        };
    }

    private static String frequencia(Fato f) {return f.presente()?"PRESENTE":"FALTOU";}
    private boolean jaDesconsiderado(Referencia r) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from vw_resolucao_frequencia_ativa
                where fonte_desconsiderada=? and fato_desconsiderado=?)
                """,Boolean.class,r.fonte().name(),r.id()));
    }
    private boolean possuiDependentes(Referencia r) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from vw_resolucao_frequencia_ativa
                where fonte_valida=? and fato_valido=?)
                """,Boolean.class,r.fonte().name(),r.id()));
    }
    private static ResourceNotFoundException naoEncontrado() {
        return new ResourceNotFoundException("Registro não encontrado.");
    }
}
