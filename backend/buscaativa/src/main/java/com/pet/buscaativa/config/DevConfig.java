package com.pet.buscaativa.config;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.BloqueioAgenda;
import com.pet.buscaativa.entities.Disponibilidade;
import com.pet.buscaativa.entities.DisponibilidadeExcecao;
import com.pet.buscaativa.entities.Endereco;
import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.MotivoEncerramento;
import com.pet.buscaativa.entities.enums.RacaCorEnum;
import com.pet.buscaativa.entities.enums.RecorrenciaGrupo;
import com.pet.buscaativa.entities.enums.SexoEnum;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.TurnoEnum;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.BloqueioAgendaRepository;
import com.pet.buscaativa.repositories.DisponibilidadeExcecaoRepository;
import com.pet.buscaativa.repositories.DisponibilidadeRepository;
import com.pet.buscaativa.repositories.GrupoTerapeuticoRepository;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.SessaoGrupoParticipanteRepository;
import com.pet.buscaativa.repositories.SessaoGrupoRepository;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.PacienteService;

import lombok.RequiredArgsConstructor;

/**
 * Base COMPLETA de dados de demonstração do perfil "dev".
 *
 * Esta versão foi montada para preencher as telas atuais do frontend:
 * Início, Pacientes, Detalhes, Histórico, Profissionais, Agenda,
 * Configuração de Agenda, Novo Agendamento e Grupos Terapêuticos.
 *
 * O H2 usado no projeto é em memória e o schema é recriado a cada execução.
 * Portanto, tudo que for cadastrado aqui reaparece automaticamente sempre que
 * o backend iniciar com o perfil dev.
 *
 * IMPORTANTE:
 * - todos os nomes/documentos abaixo são dados sintéticos para teste;
 * - este arquivo NÃO deve ser usado para dados reais de pacientes;
 * - as datas são relativas a LocalDate.now(), mantendo os cenários úteis.
 */
@Configuration
@Profile("dev")
@RequiredArgsConstructor
public class DevConfig implements CommandLineRunner {

    private final UsuarioRepository usuarioRepository;
    private final UsfReferenciaRepository usfReferenciaRepository;
    private final PacienteRepository pacienteRepository;
    private final DisponibilidadeRepository disponibilidadeRepository;
    private final DisponibilidadeExcecaoRepository disponibilidadeExcecaoRepository;
    private final BloqueioAgendaRepository bloqueioAgendaRepository;
    private final AgendamentoRepository agendamentoRepository;
    private final GrupoTerapeuticoRepository grupoTerapeuticoRepository;
    private final SessaoGrupoRepository sessaoGrupoRepository;
    private final SessaoGrupoParticipanteRepository sessaoGrupoParticipanteRepository;
    private final HistoricoPacienteRepository historicoPacienteRepository;
    private final PacienteService pacienteService;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        LocalDate hoje = LocalDate.now();

        // ---------------------------------------------------------------------
        // 1) USUÁRIOS / PROFISSIONAIS
        // ---------------------------------------------------------------------
        Usuario admin = usuario(
                "Administrador",
                "admin@pet.com",
                TipoUsuario.ADMINISTRADOR,
                UnidadeAtuacao.USF
        );

        Usuario joao = usuario(
                "João Ferreira",
                "profissional@pet.com",
                TipoUsuario.PROFISSIONAL,
                UnidadeAtuacao.CAPS_AD
        );

        Usuario recepcao = usuario(
                "Ana de Jesus",
                "recepcao@pet.com",
                TipoUsuario.RECEPCAO,
                UnidadeAtuacao.CAPS_II
        );

        Usuario maria = usuario(
                "Maria Santos",
                "maria.santos@pet.com",
                TipoUsuario.PROFISSIONAL,
                UnidadeAtuacao.CAPS_II
        );

        Usuario pedro = usuario(
                "Pedro Lima",
                "pedro.lima@pet.com",
                TipoUsuario.PROFISSIONAL,
                UnidadeAtuacao.CAPS_I
        );

        Usuario carla = usuario(
                "Carla Souza",
                "carla.souza@pet.com",
                TipoUsuario.PROFISSIONAL,
                UnidadeAtuacao.USF
        );

        usuarioRepository.saveAll(List.of(admin, joao, recepcao, maria, pedro, carla));

        // ---------------------------------------------------------------------
        // 2) USFs DE REFERÊNCIA
        // ---------------------------------------------------------------------
        UsfReferencia usfCentro = usf(
                "0000001",
                "USF TESTE Centro",
                "Centro",
                "Rua de Teste A",
                "-12.9680",
                "-39.2610"
        );

        UsfReferencia usfAndai = usf(
                "0000002",
                "USF TESTE Andaiá",
                "Andaiá",
                "Rua de Teste B",
                "-12.9700",
                "-39.2700"
        );

        UsfReferencia usfUrbis = usf(
                "0000003",
                "USF TESTE Urbis",
                "Urbis",
                "Rua de Teste C",
                "-12.9600",
                "-39.2500"
        );

        usfReferenciaRepository.saveAll(List.of(usfCentro, usfAndai, usfUrbis));
        admin.setUsfAtuacao(usfCentro);
        carla.setUsfAtuacao(usfAndai);
        usuarioRepository.saveAll(List.of(admin, carla));

        // ---------------------------------------------------------------------
        // 3) PACIENTES
        //
        // Os estados VERDE/AMARELO/VERMELHO serão recalculados posteriormente
        // a partir dos agendamentos e das frequências dos grupos.
        // ---------------------------------------------------------------------
        Paciente p1 = paciente(
                "Mariana Oliveira",
                "Helena Oliveira",
                hoje.minusYears(29),
                SexoEnum.FEMININO,
                RacaCorEnum.PARDA,
                "123456789010000",
                "12345678909",
                "(75) 99901-0001",
                endereco("Centro", "Rua das Flores", "120", "Próximo à praça"),
                false,
                TipoAcompanhamento.INDIVIDUAL,
                usfCentro,
                CapsEnum.CAPS_II
        );

        Paciente p2 = paciente(
                "Carlos Henrique Santos",
                "Marta dos Santos",
                hoje.minusYears(42),
                SexoEnum.MASCULINO,
                RacaCorEnum.PRETA,
                "198765432100003",
                "98765432100",
                "(75) 99901-0002",
                endereco("Andaiá", "Rua São José", "45", "Casa azul"),
                false,
                TipoAcompanhamento.INDIVIDUAL,
                usfAndai,
                CapsEnum.CAPS_AD
        );

        Paciente p3 = paciente(
                "Luciana Almeida",
                "Rita Almeida",
                hoje.minusYears(36),
                SexoEnum.FEMININO,
                RacaCorEnum.BRANCA,
                "212345678900009",
                "11144477735",
                "(75) 99901-0003",
                endereco("Centro", "Avenida Principal", "305", null),
                false,
                TipoAcompanhamento.INDIVIDUAL,
                usfCentro,
                CapsEnum.CAPS_II
        );

        Paciente p4 = paciente(
                "Rafael Costa",
                "Simone Costa",
                hoje.minusYears(24),
                SexoEnum.MASCULINO,
                RacaCorEnum.PARDA,
                "176543210980006",
                "52998224725",
                "(75) 99901-0004",
                endereco("Urbis", "Rua B", "18", "Bloco 2"),
                false,
                TipoAcompanhamento.GRUPO_TERAPEUTICO,
                usfUrbis,
                CapsEnum.CAPS_II
        );

        Paciente p5 = paciente(
                "Patrícia Nascimento",
                "Joana Nascimento",
                hoje.minusYears(51),
                SexoEnum.FEMININO,
                RacaCorEnum.PRETA,
                "209876543210000",
                "16899535009",
                "(75) 99901-0005",
                endereco("Andaiá", "Rua das Palmeiras", "82", null),
                false,
                TipoAcompanhamento.GRUPO_TERAPEUTICO,
                usfAndai,
                CapsEnum.CAPS_AD
        );

        Paciente p6 = paciente(
                "Eduardo Ribeiro",
                "Cláudia Ribeiro",
                hoje.minusYears(31),
                SexoEnum.MASCULINO,
                RacaCorEnum.BRANCA,
                "145678901230004",
                "39053344705",
                "(75) 99901-0006",
                endereco("Centro", "Rua Nova", "77", "Fundos"),
                false,
                TipoAcompanhamento.AMBOS,
                usfCentro,
                CapsEnum.CAPS_I
        );

        // Situação de rua: endereço nulo é proposital para testar a regra específica.
        Paciente p7 = paciente(
                "André Luiz Pereira",
                "Sônia Pereira",
                hoje.minusYears(38),
                SexoEnum.MASCULINO,
                RacaCorEnum.PARDA,
                "234567890120004",
                "86288366757",
                "(75) 99901-0007",
                null,
                true,
                TipoAcompanhamento.INDIVIDUAL,
                usfAndai,
                CapsEnum.CAPS_AD
        );

        // Paciente já encerrado, útil para testar histórico/encerramento.
        Paciente p8 = paciente(
                "Fernanda Rocha",
                "Márcia Rocha",
                hoje.minusYears(45),
                SexoEnum.FEMININO,
                RacaCorEnum.BRANCA,
                "167890123450004",
                "71428793860",
                "(75) 99901-0008",
                endereco("Urbis", "Rua C", "90", null),
                false,
                TipoAcompanhamento.INDIVIDUAL,
                usfUrbis,
                CapsEnum.CAPS_II
        );
        p8.setStatusPaciente(StatusPaciente.ALTA_TERAPEUTICA);
        p8.setClassificacaoRisco(ClassificacaoRisco.VERDE);
        p8.setDataEncerramento(hoje.minusDays(15));
        p8.setMotivoEncerramento(MotivoEncerramento.ALTA_TERAPEUTICA);
        p8.setDescricaoMotivoEncerramento("Objetivos terapêuticos alcançados.");
        p8.setProfissionalEncerramento(maria.getEmail());

        // Sem presença anterior: útil para testar filtros/indicadores de paciente sem presença recente.
        Paciente p9 = paciente(
                "Bianca Martins",
                "Vera Martins",
                hoje.minusYears(19),
                SexoEnum.FEMININO,
                RacaCorEnum.PARDA,
                "223456789010007",
                "24681357928",
                "(75) 99901-0009",
                endereco("Centro", "Rua do Sol", "22", null),
                false,
                TipoAcompanhamento.INDIVIDUAL,
                usfCentro,
                CapsEnum.CAPS_I
        );

        Paciente p10 = paciente(
                "Samuel Barbosa",
                "Denise Barbosa",
                hoje.minusYears(27),
                SexoEnum.MASCULINO,
                RacaCorEnum.PRETA,
                "189012345670000",
                "13579246828",
                "(75) 99901-0010",
                endereco("Andaiá", "Rua da Paz", "14", "Ao lado da farmácia"),
                false,
                TipoAcompanhamento.AMBOS,
                usfAndai,
                CapsEnum.CAPS_II
        );

        pacienteRepository.saveAll(List.of(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10));

        // ---------------------------------------------------------------------
        // 4) DISPONIBILIDADE SEMANAL DOS PROFISSIONAIS
        // ---------------------------------------------------------------------
        List<Disponibilidade> disponibilidades = new ArrayList<>();

        for (DayOfWeek dia : List.of(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY)) {

            disponibilidades.add(disponibilidade(joao, dia, TurnoEnum.MANHA, 8));
            disponibilidades.add(disponibilidade(joao, dia, TurnoEnum.TARDE, 8));

            disponibilidades.add(disponibilidade(maria, dia, TurnoEnum.MANHA, 6));
            disponibilidades.add(disponibilidade(maria, dia, TurnoEnum.TARDE, 6));

            disponibilidades.add(disponibilidade(pedro, dia, TurnoEnum.MANHA, 6));
            disponibilidades.add(disponibilidade(carla, dia, TurnoEnum.TARDE, 8));
        }

        disponibilidadeRepository.saveAll(disponibilidades);

        // Exceções específicas: uma data fechada e outra com capacidade ampliada.
        DisponibilidadeExcecao excecaoFechada = disponibilidadeExcecao(
                joao,
                hoje.plusDays(7),
                TurnoEnum.MANHA,
                0
        );

        DisponibilidadeExcecao excecaoExtra = disponibilidadeExcecao(
                maria,
                hoje.plusDays(7),
                TurnoEnum.TARDE,
                10
        );

        disponibilidadeExcecaoRepository.saveAll(List.of(excecaoFechada, excecaoExtra));

        // Bloqueios futuros.
        BloqueioAgenda bloqueioCapacitacao = bloqueio(
                joao,
                hoje.plusDays(14),
                hoje.plusDays(14),
                "Capacitação da equipe"
        );

        BloqueioAgenda bloqueioReuniao = bloqueio(
                maria,
                hoje.plusDays(21),
                hoje.plusDays(21),
                "Reunião interna da unidade"
        );

        bloqueioAgendaRepository.saveAll(List.of(bloqueioCapacitacao, bloqueioReuniao));

        // ---------------------------------------------------------------------
        // 5) AGENDAMENTOS INDIVIDUAIS
        // ---------------------------------------------------------------------
        List<Agendamento> agendamentos = new ArrayList<>();

        // P1: presença recente -> deve ficar VERDE.
        Agendamento p1Presenca = agendamento(
                joao, p1, hoje.minusDays(10), LocalTime.of(8, 0),
                SituacaoAtendimento.PRESENTE, null, false
        );
        Agendamento p1Hoje = agendamento(
                joao, p1, hoje, LocalTime.of(9, 0),
                SituacaoAtendimento.AGENDADO, null, false
        );
        agendamentos.add(p1Presenca);
        agendamentos.add(p1Hoje);

        // P2: duas faltas consecutivas -> AMARELO.
        Agendamento p2Presenca = agendamento(
                joao, p2, hoje.minusDays(20), LocalTime.of(8, 30),
                SituacaoAtendimento.PRESENTE, null, false
        );
        Agendamento p2Falta1 = agendamento(
                joao, p2, hoje.minusDays(5), LocalTime.of(8, 30),
                SituacaoAtendimento.FALTOU, null, false
        );
        Agendamento p2Falta2 = agendamento(
                joao, p2, hoje.minusDays(2), LocalTime.of(9, 0),
                SituacaoAtendimento.FALTOU, null, false
        );
        agendamentos.add(p2Presenca);
        agendamentos.add(p2Falta1);
        agendamentos.add(p2Falta2);

        // P3: três faltas consecutivas -> VERMELHO.
        Agendamento p3Presenca = agendamento(
                maria, p3, hoje.minusDays(35), LocalTime.of(13, 30),
                SituacaoAtendimento.PRESENTE, null, false
        );
        Agendamento p3Falta1 = agendamento(
                maria, p3, hoje.minusDays(6), LocalTime.of(14, 0),
                SituacaoAtendimento.FALTOU, null, false
        );
        Agendamento p3Falta2 = agendamento(
                maria, p3, hoje.minusDays(4), LocalTime.of(14, 30),
                SituacaoAtendimento.FALTOU, null, false
        );
        Agendamento p3Falta3 = agendamento(
                maria, p3, hoje.minusDays(1), LocalTime.of(15, 0),
                SituacaoAtendimento.FALTOU, null, false
        );
        agendamentos.add(p3Presenca);
        agendamentos.add(p3Falta1);
        agendamentos.add(p3Falta2);
        agendamentos.add(p3Falta3);

        // P6: faltou, mas depois compareceu -> contador deve zerar.
        Agendamento p6Falta = agendamento(
                pedro, p6, hoje.minusDays(9), LocalTime.of(9, 0),
                SituacaoAtendimento.FALTOU, null, false
        );
        Agendamento p6Presenca = agendamento(
                pedro, p6, hoje.minusDays(4), LocalTime.of(9, 30),
                SituacaoAtendimento.PRESENTE, null, false
        );
        agendamentos.add(p6Falta);
        agendamentos.add(p6Presenca);

        // P7: situação de rua + duas faltas consecutivas -> cenário de busca ativa.
        Agendamento p7Presenca = agendamento(
                joao, p7, hoje.minusDays(20), LocalTime.of(10, 0),
                SituacaoAtendimento.PRESENTE, null, false
        );
        Agendamento p7Falta1 = agendamento(
                joao, p7, hoje.minusDays(3), LocalTime.of(10, 0),
                SituacaoAtendimento.FALTOU, null, false
        );
        Agendamento p7Falta2 = agendamento(
                joao, p7, hoje.minusDays(1), LocalTime.of(10, 30),
                SituacaoAtendimento.FALTOU, null, false
        );
        agendamentos.add(p7Presenca);
        agendamentos.add(p7Falta1);
        agendamentos.add(p7Falta2);

        // P10: presença recente + consulta hoje.
        Agendamento p10Presenca = agendamento(
                maria, p10, hoje.minusDays(3), LocalTime.of(13, 0),
                SituacaoAtendimento.PRESENTE, null, false
        );
        Agendamento p10Hoje = agendamento(
                maria, p10, hoje, LocalTime.of(14, 0),
                SituacaoAtendimento.AGENDADO, null, false
        );
        agendamentos.add(p10Presenca);
        agendamentos.add(p10Hoje);

        // Exemplos adicionais na DATA DE HOJE para preencher todos os status
        // que a tela Agenda reconhece.
        Agendamento presenteHoje = agendamento(
                joao, p1, hoje, LocalTime.of(7, 30),
                SituacaoAtendimento.PRESENTE, null, false
        );

        Agendamento faltouHoje = agendamento(
                maria, p3, hoje, LocalTime.of(10, 0),
                SituacaoAtendimento.FALTOU, null, false
        );

        Agendamento canceladoHoje = agendamento(
                carla, p10, hoje, LocalTime.of(11, 0),
                SituacaoAtendimento.CANCELADO, null, false
        );

        Agendamento origemRemarcacaoHoje = agendamento(
                maria, p10, hoje, LocalTime.of(13, 30),
                SituacaoAtendimento.REMARCADO_ORIGEM, null, false
        );

        agendamentos.add(presenteHoje);
        agendamentos.add(faltouHoje);
        agendamentos.add(canceladoHoje);
        agendamentos.add(origemRemarcacaoHoje);

        agendamentoRepository.saveAll(agendamentos);

        // Remarcações depois da persistência do agendamento original.
        Agendamento p2Remarcado = agendamento(
                joao, p2, hoje.plusDays(3), LocalTime.of(9, 30),
                SituacaoAtendimento.REMARCADO, p2Falta2, true
        );

        Agendamento p3Remarcado = agendamento(
                maria, p3, hoje.plusDays(2), LocalTime.of(15, 30),
                SituacaoAtendimento.REMARCADO, p3Falta3, true
        );

        Agendamento remarcadoHoje = agendamento(
                maria, p10, hoje, LocalTime.of(15, 0),
                SituacaoAtendimento.REMARCADO, origemRemarcacaoHoje, false
        );

        agendamentoRepository.saveAll(List.of(p2Remarcado, p3Remarcado, remarcadoHoje));

        // ---------------------------------------------------------------------
        // 6) GRUPOS TERAPÊUTICOS
        // ---------------------------------------------------------------------
        GrupoTerapeutico grupoAnsiedade = grupo(
                "Grupo de Ansiedade - TESTE",
                maria,
                RecorrenciaGrupo.SEMANAL,
                LocalTime.of(14, 0),
                hoje.plusDays(90)
        );

        GrupoTerapeutico grupoAd = grupo(
                "Grupo Álcool e Outras Drogas - TESTE",
                joao,
                RecorrenciaGrupo.SEMANAL,
                LocalTime.of(9, 0),
                hoje.plusDays(90)
        );

        GrupoTerapeutico grupoFamilia = grupo(
                "Grupo de Família - TESTE",
                pedro,
                RecorrenciaGrupo.MENSAL,
                LocalTime.of(10, 0),
                hoje.plusDays(180)
        );

        grupoTerapeuticoRepository.saveAll(List.of(grupoAnsiedade, grupoAd, grupoFamilia));

        // Sessões realizadas e futuras.
        SessaoGrupo sessao40 = sessao(
                grupoAnsiedade, hoje.minusDays(40), LocalTime.of(14, 0), StatusSessaoGrupo.REALIZADA
        );
        SessaoGrupo sessao20 = sessao(
                grupoAnsiedade, hoje.minusDays(20), LocalTime.of(14, 0), StatusSessaoGrupo.REALIZADA
        );
        SessaoGrupo sessao8 = sessao(
                grupoAnsiedade, hoje.minusDays(8), LocalTime.of(14, 0), StatusSessaoGrupo.REALIZADA
        );
        SessaoGrupo sessao2 = sessao(
                grupoAnsiedade, hoje.minusDays(2), LocalTime.of(14, 0), StatusSessaoGrupo.REALIZADA
        );
        SessaoGrupo sessaoFutura = sessao(
                grupoAnsiedade, hoje.plusDays(5), LocalTime.of(14, 0), StatusSessaoGrupo.AGENDADA
        );

        SessaoGrupo sessaoAdFutura = sessao(
                grupoAd, hoje.plusDays(4), LocalTime.of(9, 0), StatusSessaoGrupo.AGENDADA
        );

        SessaoGrupo sessaoCancelada = sessao(
                grupoFamilia, hoje.minusDays(3), LocalTime.of(10, 0), StatusSessaoGrupo.CANCELADA
        );
        sessaoCancelada.setMotivoCancelamento("Sessão cancelada para demonstração do fluxo.");

        // Sessões na DATA DE HOJE: a tela de Grupos abre hoje por padrão.
        SessaoGrupo sessaoHojeAgendada = sessao(
                grupoAnsiedade, hoje, LocalTime.of(23, 0), StatusSessaoGrupo.AGENDADA
        );

        SessaoGrupo sessaoHojeAndamento = sessao(
                grupoAd, hoje, LocalTime.of(8, 0), StatusSessaoGrupo.AGENDADA
        );

        SessaoGrupo sessaoHojeRealizada = sessao(
                grupoFamilia, hoje, LocalTime.of(10, 0), StatusSessaoGrupo.REALIZADA
        );

        SessaoGrupo sessaoHojeCancelada = sessao(
                grupoFamilia, hoje, LocalTime.of(9, 0), StatusSessaoGrupo.CANCELADA
        );
        sessaoHojeCancelada.setMotivoCancelamento("Cancelamento de demonstração na data de hoje.");

        SessaoGrupo sessaoAnsiedadeMais7 = sessao(
                grupoAnsiedade, hoje.plusDays(7), LocalTime.of(14, 0), StatusSessaoGrupo.AGENDADA
        );

        SessaoGrupo sessaoFamiliaMais15 = sessao(
                grupoFamilia, hoje.plusDays(15), LocalTime.of(10, 0), StatusSessaoGrupo.AGENDADA
        );

        sessaoGrupoRepository.saveAll(List.of(
                sessao40, sessao20, sessao8, sessao2,
                sessaoFutura, sessaoAdFutura, sessaoCancelada,
                sessaoHojeAgendada, sessaoHojeAndamento, sessaoHojeRealizada,
                sessaoHojeCancelada, sessaoAnsiedadeMais7, sessaoFamiliaMais15
        ));

        // Frequência nos grupos.
        List<SessaoGrupoParticipante> participantes = new ArrayList<>();

        participantes.add(participante(sessao20, p4, StatusPresencaGrupo.PRESENTE));
        participantes.add(participante(sessao8, p4, StatusPresencaGrupo.FALTOU));

        participantes.add(participante(sessao40, p5, StatusPresencaGrupo.PRESENTE));
        participantes.add(participante(sessao20, p5, StatusPresencaGrupo.FALTOU));
        participantes.add(participante(sessao8, p5, StatusPresencaGrupo.FALTOU));
        participantes.add(participante(sessao2, p5, StatusPresencaGrupo.FALTOU));

        participantes.add(participante(sessao8, p6, StatusPresencaGrupo.FALTOU));
        participantes.add(participante(sessao2, p6, StatusPresencaGrupo.PRESENTE));

        participantes.add(participante(sessaoFutura, p4, StatusPresencaGrupo.NAO_REGISTRADA));
        participantes.add(participante(sessaoFutura, p5, StatusPresencaGrupo.NAO_REGISTRADA));
        participantes.add(participante(sessaoFutura, p10, StatusPresencaGrupo.NAO_REGISTRADA));
        participantes.add(participante(sessaoAdFutura, p7, StatusPresencaGrupo.NAO_REGISTRADA));

        // Participantes das sessões exibidas hoje.
        participantes.add(participante(sessaoHojeAgendada, p4, StatusPresencaGrupo.NAO_REGISTRADA));
        participantes.add(participante(sessaoHojeAgendada, p6, StatusPresencaGrupo.NAO_REGISTRADA));
        participantes.add(participante(sessaoHojeAgendada, p10, StatusPresencaGrupo.NAO_REGISTRADA));

        participantes.add(participante(sessaoHojeAndamento, p5, StatusPresencaGrupo.NAO_REGISTRADA));
        participantes.add(participante(sessaoHojeAndamento, p7, StatusPresencaGrupo.NAO_REGISTRADA));

        participantes.add(participante(sessaoHojeRealizada, p1, StatusPresencaGrupo.PRESENTE));
        participantes.add(participante(sessaoHojeRealizada, p9, StatusPresencaGrupo.FALTOU));

        participantes.add(participante(sessaoHojeCancelada, p1, StatusPresencaGrupo.NAO_REGISTRADA));

        // Participantes futuros: úteis para a lista de participantes do grupo
        // e para o indicador de inscrição futura.
        participantes.add(participante(sessaoAnsiedadeMais7, p4, StatusPresencaGrupo.NAO_REGISTRADA));
        participantes.add(participante(sessaoAnsiedadeMais7, p6, StatusPresencaGrupo.NAO_REGISTRADA));
        participantes.add(participante(sessaoFamiliaMais15, p1, StatusPresencaGrupo.NAO_REGISTRADA));
        participantes.add(participante(sessaoFamiliaMais15, p9, StatusPresencaGrupo.NAO_REGISTRADA));

        sessaoGrupoParticipanteRepository.saveAll(participantes);

        // ---------------------------------------------------------------------
        // 7) RECALCULA ASSIDUIDADE / CLASSIFICAÇÃO
        //
        // Isso usa a mesma regra de negócio da aplicação:
        // 2 faltas => amarelo; 3 faltas => vermelho;
        // e também os limites por dias sem presença.
        // ---------------------------------------------------------------------
        for (Paciente paciente : List.of(p1, p2, p3, p4, p5, p6, p7, p9, p10)) {
            pacienteService.recalcularAssiduidadePaciente(paciente);
        }

        // O gatilho de visita é um estado de fluxo; mantemos ligado no paciente
        // em situação de rua após as duas faltas para a tela de busca ativa.
        p7.setGatilhoVisitaAcionado(true);
        pacienteRepository.save(p7);

        // ---------------------------------------------------------------------
        // 8) HISTÓRICO DO PACIENTE
        // ---------------------------------------------------------------------
        List<HistoricoPaciente> historicos = new ArrayList<>();

        historicos.add(historico(
                p1, p1Presenca, null, joao,
                TipoEventoHistoricoPaciente.PRESENCA,
                SituacaoAtendimento.PRESENTE,
                hoje.minusDays(10).atTime(8, 0),
                "Paciente compareceu ao atendimento individual.",
                0
        ));

        historicos.add(historico(
                p1, p1Hoje, null, joao,
                TipoEventoHistoricoPaciente.CONSULTA_AGENDADA,
                SituacaoAtendimento.AGENDADO,
                hoje.atTime(7, 0),
                "Consulta cadastrada para a data de hoje.",
                0
        ));

        historicos.add(historico(
                p2, p2Falta1, null, joao,
                TipoEventoHistoricoPaciente.FALTA,
                SituacaoAtendimento.FALTOU,
                hoje.minusDays(5).atTime(8, 30),
                "Primeira falta consecutiva registrada.",
                1
        ));

        historicos.add(historico(
                p2, p2Falta2, null, joao,
                TipoEventoHistoricoPaciente.FALTA,
                SituacaoAtendimento.FALTOU,
                hoje.minusDays(2).atTime(9, 0),
                "Segunda falta consecutiva registrada. Paciente em atenção.",
                2
        ));

        historicos.add(historico(
                p2, p2Remarcado, null, joao,
                TipoEventoHistoricoPaciente.REMARCACAO,
                SituacaoAtendimento.REMARCADO,
                hoje.minusDays(1).atTime(11, 0),
                "Consulta remarcada após falta.",
                2
        ));

        historicos.add(historico(
                p3, p3Falta3, null, maria,
                TipoEventoHistoricoPaciente.FALTA,
                SituacaoAtendimento.FALTOU,
                hoje.minusDays(1).atTime(15, 0),
                "Terceira falta consecutiva. Busca ativa recomendada.",
                3
        ));

        historicos.add(historico(
                p4, null, sessao20, maria,
                TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO,
                null,
                hoje.minusDays(20).atTime(14, 0),
                "Presença registrada no grupo terapêutico.",
                0
        ));

        historicos.add(historico(
                p5, null, sessao2, maria,
                TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO,
                null,
                hoje.minusDays(2).atTime(14, 0),
                "Falta registrada em sessão de grupo terapêutico.",
                3
        ));

        historicos.add(historico(
                p6, p6Presenca, null, pedro,
                TipoEventoHistoricoPaciente.PRESENCA,
                SituacaoAtendimento.PRESENTE,
                hoje.minusDays(4).atTime(9, 30),
                "Paciente compareceu após falta anterior; contador reiniciado.",
                0
        ));

        historicos.add(historico(
                p7, p7Falta2, null, joao,
                TipoEventoHistoricoPaciente.BUSCA_ATIVA,
                SituacaoAtendimento.FALTOU,
                hoje.minusDays(1).atTime(10, 30),
                "Duas faltas consecutivas em paciente em situação de rua. Busca em ponto de referência recomendada.",
                2
        ));

        historicos.add(historico(
                p8, null, null, maria,
                TipoEventoHistoricoPaciente.SITUACAO_ATUALIZADA,
                null,
                hoje.minusDays(15).atTime(16, 0),
                "Acompanhamento encerrado por alta terapêutica.",
                0
        ));

        historicos.add(historico(
                p10, origemRemarcacaoHoje, null, maria,
                TipoEventoHistoricoPaciente.REMARCACAO,
                SituacaoAtendimento.REMARCADO_ORIGEM,
                hoje.atTime(13, 35),
                "Agendamento anterior substituído por um novo horário.",
                0
        ));

        historicoPacienteRepository.saveAll(historicos);
    }

    // =========================================================================
    // HELPERS
    // =========================================================================
    // Todos os dados são sintéticos e destinados somente ao perfil dev.

    private Usuario usuario(
            String nome,
            String email,
            TipoUsuario tipo,
            UnidadeAtuacao unidade) {

        Usuario usuario = new Usuario();
        usuario.setNome(nome);
        usuario.setEmail(email);
        usuario.setSenha(passwordEncoder.encode("123456"));
        usuario.setTipoUsuario(tipo);
        usuario.setUnidadeAtuacao(unidade);
        return usuario;
    }

    private UsfReferencia usf(
            String cnes,
            String nome,
            String bairro,
            String logradouro,
            String latitude,
            String longitude) {

        UsfReferencia usf = new UsfReferencia();
        usf.setCnes(cnes);
        usf.setNomeUsf(nome);
        usf.setBairro(bairro);
        usf.setLogradouro(logradouro);
        usf.setLatitude(latitude);
        usf.setLongitude(longitude);
        return usf;
    }

    private Paciente paciente(
            String nome,
            String nomeMae,
            LocalDate nascimento,
            SexoEnum sexo,
            RacaCorEnum racaCor,
            String cns,
            String cpf,
            String telefone,
            Endereco endereco,
            boolean situacaoRua,
            TipoAcompanhamento acompanhamento,
            UsfReferencia usf,
            CapsEnum caps) {

        Paciente paciente = new Paciente();
        paciente.setNome(nome);
        paciente.setNomeMae(nomeMae);
        paciente.setDataNascimento(nascimento);
        paciente.setSexo(sexo);
        paciente.setRacacor(racaCor);
        paciente.setCns(cns);
        paciente.setCpf(cpf);
        paciente.setTelefone(telefone);
        paciente.setEndereco(endereco);
        paciente.setSituacaoRua(situacaoRua);
        paciente.setTipoAcompanhamento(acompanhamento);
        paciente.setCountFaltas(0);
        paciente.setStatusPaciente(StatusPaciente.ATIVO);
        paciente.setUsfReferencia(usf);
        paciente.setCapsReferencia(caps);
        paciente.setClassificacaoRisco(ClassificacaoRisco.VERDE);
        paciente.setGatilhoVisitaAcionado(false);
        return paciente;
    }

    private Endereco endereco(
            String bairro,
            String logradouro,
            String numero,
            String complemento) {

        Endereco endereco = new Endereco();
        endereco.setCidade("Santo Antônio de Jesus");
        endereco.setEstado("BA");
        endereco.setBairro(bairro);
        endereco.setLogradouro(logradouro);
        endereco.setNumero(numero);
        endereco.setComplemento(complemento);
        endereco.setCep("44570-000");
        return endereco;
    }

    private Disponibilidade disponibilidade(
            Usuario usuario,
            DayOfWeek dia,
            TurnoEnum turno,
            int capacidade) {

        Disponibilidade disponibilidade = new Disponibilidade();
        disponibilidade.setUsuario(usuario);
        disponibilidade.setDiaDaSemana(dia);
        disponibilidade.setTurno(turno);
        disponibilidade.setCapacidade(capacidade);
        return disponibilidade;
    }

    private DisponibilidadeExcecao disponibilidadeExcecao(
            Usuario usuario,
            LocalDate data,
            TurnoEnum turno,
            int capacidade) {

        DisponibilidadeExcecao excecao = new DisponibilidadeExcecao();
        excecao.setUsuario(usuario);
        excecao.setData(data);
        excecao.setTurno(turno);
        excecao.setCapacidade(capacidade);
        return excecao;
    }

    private BloqueioAgenda bloqueio(
            Usuario usuario,
            LocalDate inicio,
            LocalDate fim,
            String motivo) {

        BloqueioAgenda bloqueio = new BloqueioAgenda();
        bloqueio.setUsuario(usuario);
        bloqueio.setDataInicio(inicio);
        bloqueio.setDataFim(fim);
        bloqueio.setMotivoBloqueio(motivo);
        return bloqueio;
    }

    private Agendamento agendamento(
            Usuario usuario,
            Paciente paciente,
            LocalDate data,
            LocalTime hora,
            SituacaoAtendimento situacao,
            Agendamento original,
            boolean remarcacaoAposFalta) {

        Agendamento agendamento = new Agendamento();
        agendamento.setUsuario(usuario);
        agendamento.setPaciente(paciente);
        agendamento.setDataAgendamento(data);
        agendamento.setHoraAtendimento(hora);
        agendamento.setTurnoAgendamento(
                hora.isBefore(LocalTime.NOON) ? TurnoEnum.MANHA : TurnoEnum.TARDE
        );
        agendamento.setSituacaoAtendimento(situacao);
        agendamento.setAgendamentoOriginal(original);
        agendamento.setRemarcacaoAposFalta(remarcacaoAposFalta);
        return agendamento;
    }

    private GrupoTerapeutico grupo(
            String tema,
            Usuario coordenador,
            RecorrenciaGrupo recorrencia,
            LocalTime horario,
            LocalDate fimRecorrencia) {

        GrupoTerapeutico grupo = new GrupoTerapeutico();
        grupo.setTema(tema);
        grupo.setCoordenador(coordenador);
        grupo.setRecorrencia(recorrencia);
        grupo.setHorarioPadrao(horario);
        grupo.setDataFimRecorrencia(fimRecorrencia);
        grupo.setAtivo(true);
        return grupo;
    }

    private SessaoGrupo sessao(
            GrupoTerapeutico grupo,
            LocalDate data,
            LocalTime horario,
            StatusSessaoGrupo status) {

        SessaoGrupo sessao = new SessaoGrupo();
        sessao.setGrupo(grupo);
        sessao.setDataSessao(data);
        sessao.setHorario(horario);
        sessao.setStatus(status);
        return sessao;
    }

    private SessaoGrupoParticipante participante(
            SessaoGrupo sessao,
            Paciente paciente,
            StatusPresencaGrupo status) {

        SessaoGrupoParticipante participante = new SessaoGrupoParticipante();
        participante.setSessaoGrupo(sessao);
        participante.setPaciente(paciente);
        participante.setStatusPresenca(status);
        return participante;
    }

    private HistoricoPaciente historico(
            Paciente paciente,
            Agendamento agendamento,
            SessaoGrupo sessao,
            Usuario profissional,
            TipoEventoHistoricoPaciente tipo,
            SituacaoAtendimento situacao,
            LocalDateTime ocorridoEm,
            String descricao,
            Integer numeroFalta) {

        HistoricoPaciente historico = new HistoricoPaciente();
        historico.setPaciente(paciente);
        historico.setAgendamento(agendamento);
        historico.setSessaoGrupo(sessao);
        historico.setProfissional(profissional);
        historico.setTipo(tipo);
        historico.setSituacaoAtendimento(situacao);
        historico.setOcorridoEm(ocorridoEm);
        historico.setDescricao(descricao);
        historico.setNumeroFaltaConsecutiva(numeroFalta);
        return historico;
    }
}
