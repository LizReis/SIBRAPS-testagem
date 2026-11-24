package com.pet.buscaativa.services.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.migration.PlanoCoordenacaoEscritaClinica;
import com.pet.buscaativa.migration.PlanoCoordenacaoEscritaClinica.Descoberta;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.GrupoTerapeuticoRepository;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

@Component
@Profile("test & ensaio-estado-local")
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
class CoordenacaoEscritaClinicaEnsaio {
    private final AgendaAssistencialLocks sujeitos;
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final VinculoAssistencialRepository vinculos;
    private final GrupoTerapeuticoRepository grupos;
    private final AgendamentoRepository agenda;
    private final EntityManager em;

    record Lotacao(UUID usuario, TipoUsuario perfil, UnidadeAtuacao unidade, Long usf) {}
    record IdentidadeVinculo(UUID paciente, UUID vinculo) {}
    record Inicio(Paciente paciente, AtoresEstadoClinicoLocalEnsaio.Atores atores, VinculoAssistencial existente) {}
    record Bloqueados<T>(T relacoes, AtoresEstadoClinicoLocalEnsaio.Atores atores,
            Map<UUID, VinculoAssistencial> vinculos, Map<Long, SessaoGrupo> sessoes, Map<Long, Agendamento> agenda) {}

    Inicio paraInicio(AtoresEstadoClinicoLocalEnsaio.Contexto contexto, UUID paciente, UUID referencia) {
        // O resultado idempotente depende de saber se o vínculo passou a existir enquanto aguardávamos.
        var pessoa = sujeitos.travarPaciente(paciente);
        Supplier<Descoberta<List<IdentidadeVinculo>>> descobrir = () -> {
            var locais = identificar(List.of(paciente), contexto.unidade(), false);
            var referenciaNova = locais.isEmpty() ? referencia : null;
            return descoberta(locais, List.of(paciente), contexto, referenciaNova, List.of(), List.of(),
                    List.of(), List.of(), locais);
        };
        var antes = descobrir.get();
        var bloqueados = bloquear(antes, descobrir, contexto, antes.relacoes().isEmpty() ? referencia : null, true);
        var existente = bloqueados.vinculos().get(paciente);
        return new Inicio(pessoa, bloqueados.atores(), existente);
    }

    Bloqueados<List<IdentidadeVinculo>> paraVinculo(AtoresEstadoClinicoLocalEnsaio.Contexto contexto,
            UUID paciente, UUID vinculo, UUID referencia) {
        if (paciente == null || vinculo == null) throw naoEncontrado();
        return bloquear(() -> {
            var locais = identificar(List.of(paciente), contexto.unidade(), true);
            if (!locais.getFirst().vinculo().equals(vinculo)) throw naoEncontrado();
            return descoberta(locais, List.of(paciente), contexto, referencia, List.of(), List.of(),
                    List.of(), List.of(), locais);
        }, contexto, referencia);
    }

    <T> Bloqueados<T> bloquear(Supplier<Descoberta<T>> descobrir,
            AtoresEstadoClinicoLocalEnsaio.Contexto contexto, UUID referencia) {
        return bloquear(descobrir.get(), descobrir, contexto, referencia, false);
    }

    private <T> Bloqueados<T> bloquear(Descoberta<T> antes, Supplier<Descoberta<T>> descobrir,
            AtoresEstadoClinicoLocalEnsaio.Contexto contexto, UUID referencia, boolean identidadeTravada) {
        var plano = antes.plano();
        var lotacoes = identificarLotacoes(plano.usuarios());
        if (!identidadeTravada) plano.pacientes().forEach(sujeitos::travarPaciente);

        var usuarios = new LinkedHashMap<UUID, Usuario>();
        for (UUID id : plano.usuarios()) usuarios.put(id, sujeitos.travarUsuario(id));
        var autorizados = atores.validar(contexto, referencia, usuarios);

        var locais = new LinkedHashMap<UUID, VinculoAssistencial>();
        for (UUID id : plano.vinculos()) {
            var local = vinculos.findByIdPublicoForUpdate(id, contexto.unidade())
                    .orElseThrow(CoordenacaoEscritaClinicaEnsaio::naoEncontrado);
            reler(local);
            if (!contexto.unidade().equals(local.getUnidadeChave())
                    || !ProjecaoClinicaVinculoService.chaveCanonica(local)
                    || !plano.pacientes().contains(local.getPaciente().getIdPublico())
                    || locais.put(local.getPaciente().getIdPublico(), local) != null)
                throw naoEncontrado();
        }
        for (Long id : plano.grupos()) reler(grupos.findByIdForUpdate(id).orElseThrow(CoordenacaoEscritaClinicaEnsaio::mudou));
        var sessoesTravadas = new LinkedHashMap<Long, SessaoGrupo>();
        for (Long id : plano.sessoes()) {
            var sessao = em.find(SessaoGrupo.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (sessao == null) throw mudou();
            reler(sessao);
            sessoesTravadas.put(id, sessao);
        }
        var agendaTravada = new LinkedHashMap<Long, Agendamento>();
        for (Long id : plano.agendamentos()) {
            var agendamento = agenda.findByIdForUpdate(id).orElseThrow(CoordenacaoEscritaClinicaEnsaio::mudou);
            reler(agendamento);
            agendaTravada.put(id, agendamento);
        }
        var depois = descobrir.get();
        if (!plano.mesmosRecursos(depois.plano()) || !antes.relacoes().equals(depois.relacoes())
                || !lotacoes.equals(identificarLotacoes(plano.usuarios()))) throw mudou();
        return new Bloqueados<>(depois.relacoes(), autorizados, Map.copyOf(locais),
                Map.copyOf(sessoesTravadas), Map.copyOf(agendaTravada));
    }

    private List<Lotacao> identificarLotacoes(List<UUID> usuarios) {
        return em.createQuery("""
                select new com.pet.buscaativa.services.impl.CoordenacaoEscritaClinicaEnsaio$Lotacao(
                    u.idPublico, u.tipoUsuario, u.unidadeAtuacao, usf.id)
                from Usuario u left join u.usfAtuacao usf where u.idPublico in :usuarios order by u.idPublico
                """, Lotacao.class).setParameter("usuarios", usuarios).getResultList();
    }

    List<IdentidadeVinculo> identificar(List<UUID> pacientes, String unidade, boolean obrigatorio) {
        var encontrados = em.createQuery("""
                select new com.pet.buscaativa.services.impl.CoordenacaoEscritaClinicaEnsaio$IdentidadeVinculo(
                    v.paciente.idPublico, v.idPublico)
                from VinculoAssistencial v where v.paciente.idPublico in :pacientes and v.unidadeChave = :unidade
                order by v.paciente.idPublico
                """, IdentidadeVinculo.class).setParameter("pacientes", pacientes)
                .setParameter("unidade", unidade).getResultList();
        if (obrigatorio && encontrados.size() != pacientes.size()) throw naoEncontrado();
        return List.copyOf(encontrados);
    }

    static <T> Descoberta<T> descoberta(List<IdentidadeVinculo> locais, List<UUID> pacientes,
            AtoresEstadoClinicoLocalEnsaio.Contexto contexto, UUID referencia, List<UUID> responsaveis,
            List<Long> grupos, List<Long> sessoes, List<Long> agenda, T relacoes) {
        var usuarios = new ArrayList<>(responsaveis);
        usuarios.add(contexto.ator());
        if (referencia != null) usuarios.add(referencia);
        return new Descoberta<>(new PlanoCoordenacaoEscritaClinica(pacientes, usuarios,
                locais.stream().map(IdentidadeVinculo::vinculo).toList(), grupos, sessoes, agenda), relacoes);
    }

    static void validarVersao(Integer informada, Integer atual) {
        if (informada == null || !Objects.equals(informada, atual))
            throw new ValidationException("Versão local divergente. Consulte o vínculo novamente.");
    }

    private void reler(Object entidade) {
        em.refresh(entidade, LockModeType.PESSIMISTIC_WRITE);
    }

    private static ResourceNotFoundException naoEncontrado() {
        return new ResourceNotFoundException("Vínculo não encontrado.");
    }

    static ConflictException mudou() {
        return new ConflictException("Os recursos do comando clínico foram alterados. Consulte os dados novamente.");
    }
}
