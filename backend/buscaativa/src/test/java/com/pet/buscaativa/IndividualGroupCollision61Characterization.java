package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;

/** Etapa 6.1: diagnóstico opt-in. Asserções representam a regra desejada, não o defeito atual. */
class IndividualGroupCollision61Characterization extends CancellationRegularizationSupport {
    private static final String GROUP = "/api/grupos";
    private static final LocalTime TIME = LocalTime.of(15, 0);
    private static final LocalTime LATER = LocalTime.of(15, 30);
    @Autowired GrupoTerapeuticoRepository groups61;
    @Autowired SessaoGrupoRepository sessions61;
    @Autowired SessaoGrupoParticipanteRepository participants61;

    @BeforeEach void bothModalities() {
        // Não usar tipo INDIVIDUAL como pretexto para uma recusa de inscrição em grupo.
        tx(() -> {
            for (var p : List.of(patient, other)) {
                var managed = patients.findById(p.getId()).orElseThrow();
                managed.setTipoAcompanhamento(TipoAcompanhamento.AMBOS);
                patients.saveAndFlush(managed);
            }
            return null;
        });
        patient = patients.findById(patient.getId()).orElseThrow();
        other = patients.findById(other.getId()).orElseThrow();
    }

    private record GroupRef(Long group, Long session, Integer version, Integer sessionVersion) {}
    private record Counts(long appointments, long groups, long sessions, long participants,
                          long history, long corrections, long regularizations) {}

    private Counts counts() {
        return new Counts(appointments.count(), groups61.count(), sessions61.count(), participants61.count(),
                history.count(), corrections.count(), regularizations.count());
    }

    private MockHttpServletResponse http(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        builder.header("Authorization", "Bearer " + tokens.gerarToken(admin));
        if (body != null) builder.contentType("application/json").content(json.writeValueAsString(body));
        return mvc.perform(builder).andReturn().getResponse();
    }

    private void expect(String id, int status, MockHttpServletResponse response) throws Exception {
        int actual = businessStatus(response);
        System.out.printf("E61 %s expected=%d actual=%d%n", id, status, actual);
        assertEquals(status, actual, id + " resposta: " + response.getContentAsString());
    }

    @FunctionalInterface private interface Operation { MockHttpServletResponse execute() throws Exception; }

    private void refuse(String id, Operation operation) throws Exception {
        var before = database();
        var countBefore = counts();
        var response = operation.execute();
        int actual = businessStatus(response);
        var countAfter = counts();
        boolean unchanged = before.equals(database());
        System.out.printf("E61 %s expected=409 actual=%d unchanged=%s before=%s after=%s%n",
                id, actual, unchanged, countBefore, countAfter);
        assertAll(id,
                () -> assertEquals(409, actual, "Resposta: " + response.getContentAsString()),
                () -> assertTrue(unchanged, "Uma operação recusada deve preservar todas as tabelas públicas, inclusive histórico e auditorias."));
    }

    private CriarGrupoDTO groupDto(Usuario coordinator, LocalDate day, LocalTime time, List<UUID> ids) {
        return new CriarGrupoDTO("Grupo sintético 6.1", coordinator.getIdPublico(), RecorrenciaGrupo.UNICA,
                day, null, time, ids);
    }

    private GroupRef createGroup(CriarGrupoDTO dto) throws Exception {
        var response = http(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP), dto);
        assertEquals(200, businessStatus(response), "Fixture de grupo: " + response.getContentAsString());
        long id = json.readTree(response.getContentAsString()).get("id").asLong();
        return tx(() -> {
            var g = groups61.findById(id).orElseThrow();
            var s = sessions61.findByGrupoOrderByDataSessaoDesc(g).stream()
                    .min(Comparator.comparing(SessaoGrupo::getDataSessao)).orElseThrow();
            return new GroupRef(id, s.getId(), g.getVersion(), s.getVersion());
        });
    }

    private GroupRef group(Usuario coordinator, LocalDate day, LocalTime time, Paciente... people) throws Exception {
        return createGroup(groupDto(coordinator, day, time,
                java.util.Arrays.stream(people).map(Paciente::getIdPublico).toList()));
    }

    private AgendamentoDTO appointmentDto(Paciente person, Usuario owner, LocalDate day, LocalTime time, Long original) {
        return new AgendamentoDTO(null, owner.getIdPublico(), null, person.getIdPublico(), null, null,
                day, TurnoEnum.TARDE, time, original == null ? SituacaoAtendimento.AGENDADO : SituacaoAtendimento.REMARCADO,
                original, null);
    }

    private MockHttpServletResponse createIndividual(Paciente person, Usuario owner, LocalDate day,
            LocalTime time, Long original) throws Exception {
        return http(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos"),
                appointmentDto(person, owner, day, time, original));
    }

    private void individual(Paciente person, Usuario owner, LocalDate day, LocalTime time) throws Exception {
        var response = createIndividual(person, owner, day, time, null);
        assertEquals(201, businessStatus(response), "Fixture individual: " + response.getContentAsString());
        long id = json.readTree(response.getContentAsString()).get("id").asLong();
        assertTrue(appointment(id).getOcupaVaga());
    }

    private MockHttpServletResponse add(GroupRef g, Paciente person) throws Exception {
        return http(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                GROUP + "/sessoes/" + g.session() + "/participantes"), new AdicionarParticipanteDTO(person.getIdPublico()));
    }

    private void cancel(GroupRef g) throws Exception {
        var result = http(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                GROUP + "/sessoes/" + g.session() + "/confirmacao-ocorrencia"),
                new ConfirmarOcorrenciaGrupoDTO(false, List.of(), "Cancelamento sintético de controle.", g.sessionVersion()));
        assertEquals(200, businessStatus(result), "Fixture cancelada: " + result.getContentAsString());
        assertEquals(StatusSessaoGrupo.CANCELADA, sessions61.findById(g.session()).orElseThrow().getStatus());
    }

    private void groupConflict(boolean samePatient) throws Exception {
        // Isola o sujeito da colisão: paciente OU profissional, nunca os dois juntos.
        group(samePatient ? colleague : professional, DAY, TIME, samePatient ? patient : other);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void ig01And02CreatingIndividualMustRespectExistingGroup(boolean samePatient) throws Exception {
        groupConflict(samePatient);
        refuse(samePatient ? "IG-01" : "IG-02", () -> createIndividual(patient, professional, DAY, TIME, null));
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void ig03And04ReschedulingMustRespectExistingGroup(boolean samePatient) throws Exception {
        groupConflict(samePatient);
        var original = tx(() -> seed(patient, professional, SituacaoAtendimento.FALTOU, DAY.minusDays(2), "08:00"));
        refuse(samePatient ? "IG-03" : "IG-04", () -> createIndividual(patient, professional, DAY, TIME, original.getId()));
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void ig05And06RecoveryMustRespectExistingGroup(boolean samePatient) throws Exception {
        var a = canceled(true);
        groupConflict(samePatient);
        refuse(samePatient ? "IG-05" : "IG-06", () -> recover(admin, a, recovery(a)));
    }

    @Test void ig09CanceledGroupDoesNotBlockIndividual() throws Exception {
        cancel(group(professional, DAY, TIME, patient));
        expect("IG-09", 201, createIndividual(patient, professional, DAY, TIME, null));
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void ig10And11CompatibleIndividualAndGroupRemainAllowed(boolean differentTime) throws Exception {
        group(differentTime ? professional : colleague, DAY, TIME, differentTime ? patient : other);
        expect(differentTime ? "IG-10" : "IG-11", 201,
                createIndividual(patient, professional, DAY, differentTime ? LATER : TIME, null));
    }

    @Test void gi01AddingParticipantMustRespectIndividual() throws Exception {
        var g = group(colleague, DAY, TIME);
        individual(patient, professional, DAY, TIME);
        refuse("GI-01", () -> add(g, patient));
    }

    @Test void gi02FutureEnrollmentMustRejectWholeSeriesIfLastSessionConflicts() throws Exception {
        var last = DAY.plusWeeks(1);
        var g = createGroup(new CriarGrupoDTO("Série sintética 6.1", colleague.getIdPublico(),
                RecorrenciaGrupo.SEMANAL, DAY, last, TIME, List.of()));
        tx(() -> { capacity(professional, last, TurnoEnum.TARDE, 10); return null; });
        individual(patient, professional, last, TIME);
        refuse("GI-02", () -> http(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                GROUP + "/" + g.group() + "/inscricoes-futuras"), new InscricaoFuturaGrupoDTO(patient.getIdPublico())));
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void gi03And05CreatingGroupMustRespectExistingIndividual(boolean samePatient) throws Exception {
        individual(patient, professional, DAY, TIME);
        var dto = groupDto(samePatient ? colleague : professional, DAY, TIME,
                List.of((samePatient ? patient : other).getIdPublico()));
        refuse(samePatient ? "GI-03" : "GI-05", () -> http(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP), dto));
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void gi04And05NextSessionMustRespectExistingIndividual(boolean samePatient) throws Exception {
        var g = group(samePatient ? colleague : professional, DAY, TIME);
        var next = DAY.plusWeeks(1);
        tx(() -> { capacity(professional, next, TurnoEnum.TARDE, 10); return null; });
        individual(patient, professional, next, TIME);
        var dto = new NovaSessaoDTO(g.group(), next, TIME, List.of((samePatient ? patient : other).getIdPublico()));
        refuse(samePatient ? "GI-04" : "GI-05-NEXT", () -> http(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP + "/sessoes"), dto));
    }

    @Test void gi06CoordinatorTransferMustRespectIndividual() throws Exception {
        var g = group(colleague, DAY, TIME, other);
        individual(patient, professional, DAY, TIME);
        var dto = new AtualizarGrupoTerapeuticoDTO("Grupo sintético 6.1", professional.getIdPublico(),
                DAY, TIME, RecorrenciaGrupo.UNICA, null, g.version());
        refuse("GI-06", () -> http(put(GROUP + "/" + g.group()), dto));
    }

    @Test void gi10CanceledIndividualWithoutFactDoesNotBlockEnrollment() throws Exception {
        var a = canceled(true);
        assertFalse(appointment(a.getId()).getOcupaVaga());
        var g = group(colleague, DAY, TIME);
        expect("GI-10", 200, add(g, patient));
        assertTrue(participants61.findPacienteIds(g.session()).contains(patient.getIdPublico()));
    }

    @Test void gr01SamePatientCannotJoinDistinctSessionsAtSameTime() throws Exception {
        group(professional, DAY, TIME, patient);
        var second = group(colleague, DAY, TIME);
        refuse("GR-01", () -> add(second, patient));
    }

    @Test void gr02SameCoordinatorCannotCreateTwoSimultaneousSessions() throws Exception {
        group(professional, DAY, TIME, patient);
        refuse("GR-02", () -> http(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP),
                groupDto(professional, DAY, TIME, List.of(other.getIdPublico()))));
    }

    @Test void gr03SeveralPatientsInOneSessionAreLegitimate() throws Exception {
        var g = group(professional, DAY, TIME, patient);
        expect("GR-03", 200, add(g, other));
        assertEquals(java.util.Set.of(patient.getIdPublico(), other.getIdPublico()),
                new java.util.HashSet<>(participants61.findPacienteIds(g.session())));
    }

    @Test void gr04DifferentPatientsAndCoordinatorsAreCompatible() throws Exception {
        group(professional, DAY, TIME, patient);
        var second = group(colleague, DAY, TIME, other);
        assertEquals(List.of(other.getIdPublico()), participants61.findPacienteIds(second.session()));
        System.out.println("E61 GR-04 two independent groups created");
    }

    @Test void gr05SamePatientAtDifferentStartTimesIsAllowedByCurrentPrecision() throws Exception {
        group(professional, DAY, TIME, patient);
        var second = group(colleague, DAY, LATER, patient);
        assertEquals(List.of(patient.getIdPublico()), participants61.findPacienteIds(second.session()));
        System.out.println("E61 GR-05 distinct start times; duration is not inferred");
    }

    @Test void gr06CanceledGroupDoesNotBlockNewEnrollment() throws Exception {
        cancel(group(professional, DAY, TIME, patient));
        var g = group(colleague, DAY, TIME);
        expect("GR-06", 200, add(g, patient));
    }

    @Test void gr07CoordinatorTransferCannotConflictWithAnotherGroup() throws Exception {
        group(professional, DAY, TIME, patient);
        var g = group(colleague, DAY, TIME, other);
        var dto = new AtualizarGrupoTerapeuticoDTO("Grupo sintético 6.1", professional.getIdPublico(),
                DAY, TIME, RecorrenciaGrupo.UNICA, null, g.version());
        refuse("GR-07", () -> http(put(GROUP + "/" + g.group()), dto));
    }

    @Test void gr08NextSessionCannotConflictWithAnotherGroupParticipant() throws Exception {
        group(professional, DAY.plusWeeks(1), TIME, patient);
        var g = group(colleague, DAY, TIME);
        refuse("GR-08", () -> http(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP + "/sessoes"),
                new NovaSessaoDTO(g.group(), DAY.plusWeeks(1), TIME, List.of(patient.getIdPublico()))));
    }

    @Test void gr09RepeatedEnrollmentCannotDuplicateParticipation() throws Exception {
        var g = group(professional, DAY, TIME, patient);
        // Current contract explicitly rejects repetitions; keep the preexisting protection.
        refuse("GR-09", () -> add(g, patient));
    }
}
