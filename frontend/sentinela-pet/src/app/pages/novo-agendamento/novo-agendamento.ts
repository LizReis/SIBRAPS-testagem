import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  Subject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  finalize,
  map,
  of,
  switchMap,
  takeUntil,
} from 'rxjs';

import {
  AgendamentoService,
  HorarioDisponivel,
  HorariosDisponiveisPayload,
  NovoAgendamentoPayload,
  StandardError,
  ValidationError,
  VagasPorTurno,
} from '../../services/agendamento-service';
import {
  PacientePayload,
  PacienteService,
} from '../../services/paciente/paciente-service';
import {
  ProfissionalPayload,
  ProfissionalService,
} from '../../services/profissional/profissional-service';

@Component({
  selector: 'app-novo-agendamento',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './novo-agendamento.html',
  styleUrl: './novo-agendamento.css',
})
export class NovoAgendamento implements OnInit, OnDestroy {
  termoPesquisaPaciente = '';
  resultadosPacientes: PacientePayload[] = [];
  pacienteSelecionado: PacientePayload | null = null;
  buscandoPaciente = false;
  pesquisaRealizada = false;

  profissionais: ProfissionalPayload[] = [];
  profissionalSelecionadoId: string | null = null;

  dataSelecionada = '';
  readonly dataMinima = this.dataLocalHoje();

  vagas: VagasPorTurno | null = null;
  consultandoVagas = false;
  turnoSelecionado: string | null = null;

  horarios: HorarioDisponivel[] = [];
  horariosPayload: HorariosDisponiveisPayload | null = null;
  consultandoHorarios = false;
  erroHorarios: string | null = null;
  horaAtendimento = '';

  agendamentoOriginalId: number | null = null;
  carregandoRemarcacao = false;
  sugestoesRemarcacao: string[] = [];

  salvando = false;
  erroGeral: string | null = null;
  errosPorCampo: Record<string, string> = {};

  private readonly pesquisa$ = new Subject<string>();
  private readonly horarioConsulta$ = new Subject<{
    usuarioId: string;
    data: string;
    turno: string;
  }>();
  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly router: Router,
    private readonly route: ActivatedRoute,
    private readonly pacienteService: PacienteService,
    private readonly profissionalService: ProfissionalService,
    private readonly agendamentoService: AgendamentoService,
  ) {}

  ngOnInit(): void {
    this.configurarPesquisa();
    this.configurarConsultaHorarios();

    this.agendamentoOriginalId =
      Number(this.route.snapshot.queryParamMap.get('agendamentoOriginalId')) || null;

    this.carregarProfissionais();

    const pacienteId = this.route.snapshot.queryParamMap.get('pacienteId');
    if (pacienteId) {
      this.pacienteService.buscarPorId(pacienteId).subscribe({
        next: paciente => {
          this.pacienteSelecionado = paciente;
          this.termoPesquisaPaciente = paciente.nome;
          this.resultadosPacientes = [paciente];
        },
        error: () => this.erroGeral = 'Não foi possível pré-selecionar o paciente informado.',
      });
    }

    if (this.agendamentoOriginalId) {
      this.carregarAgendamentoOriginal(this.agendamentoOriginalId);
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get modoRemarcacao(): boolean {
    return this.agendamentoOriginalId !== null;
  }

  private configurarPesquisa(): void {
    this.pesquisa$
      .pipe(
        map((termo) => termo.trim()),
        debounceTime(350),
        distinctUntilChanged(),
        switchMap((termo) => {
          const digitos = termo.replace(/\D/g, '');
          const temLetras = /[A-Za-zÀ-ÿ]/.test(termo);

          this.pesquisaRealizada = false;

          const nomeInvalido = temLetras && termo.length < 3;
          const documentoInvalido =
            !temLetras && ![11, 15].includes(digitos.length);

          if (nomeInvalido || documentoInvalido) {
            this.buscandoPaciente = false;
            return of<PacientePayload[]>([]);
          }

          this.buscandoPaciente = true;

          const consulta = temLetras
            ? this.pacienteService.buscarPorNome(termo)
            : digitos.length === 11
              ? this.pacienteService
                  .buscarPorCpf(digitos)
                  .pipe(map((paciente) => [paciente]))
              : this.pacienteService
                  .buscarPorCns(digitos)
                  .pipe(map((paciente) => [paciente]));

          return consulta.pipe(
            catchError(() => of<PacientePayload[]>([])),
            finalize(() => {
              this.buscandoPaciente = false;
              this.pesquisaRealizada = true;
            }),
          );
        }),
        takeUntil(this.destroy$),
      )
      .subscribe((pacientes) => {
        this.resultadosPacientes = pacientes;
      });
  }

  private configurarConsultaHorarios(): void {
    this.horarioConsulta$
      .pipe(
        switchMap((consulta) => {
          this.consultandoHorarios = true;
          this.erroHorarios = null;

          return this.agendamentoService
            .buscarHorariosDisponiveis(
              consulta.usuarioId,
              consulta.data,
              consulta.turno,
            )
            .pipe(
              catchError((erro: HttpErrorResponse) => {
                this.erroHorarios =
                  erro.error?.message || 'Não foi possível consultar os horários.';
                return of<HorariosDisponiveisPayload | null>(null);
              }),
              finalize(() => {
                this.consultandoHorarios = false;
              }),
            );
        }),
        takeUntil(this.destroy$),
      )
      .subscribe((payload) => {
        this.horariosPayload = payload;
        this.horarios = payload?.horarios ?? [];
      });
  }

  pesquisarPaciente(): void {
    this.pesquisa$.next(this.termoPesquisaPaciente);
  }

  selecionarPaciente(paciente: PacientePayload): void {
    this.pacienteSelecionado = paciente;
    this.resultadosPacientes = [];
    this.termoPesquisaPaciente = '';
    this.pesquisaRealizada = false;
  }

  trocarPaciente(): void {
    if (this.modoRemarcacao) {
      return;
    }

    this.pacienteSelecionado = null;
  }

  carregarProfissionais(): void {
    this.profissionalService.listarParaSelecao().subscribe({
      next: (profissionais) => {
        this.profissionais = profissionais;
      },
      error: (erro) => {
        console.error('Erro ao carregar profissionais', erro);
        this.erroGeral = 'Não foi possível carregar a lista de profissionais.';
      },
    });
  }

  carregarAgendamentoOriginal(id: number): void {
    this.carregandoRemarcacao = true;

    this.agendamentoService.buscarPorId(id).subscribe({
      next: (agendamento) => {
        this.profissionalSelecionadoId = agendamento.usuarioId;

        this.pacienteService.buscarPorId(agendamento.pacienteId).subscribe({
          next: (paciente) => {
            this.pacienteSelecionado = paciente;
          },
          error: () => {
            this.pacienteSelecionado = {
              idPublico: agendamento.pacienteId,
              nome: agendamento.nomePaciente,
              tipoAcompanhamento: agendamento.tipoAcompanhamento,
            } as PacientePayload;
          },
        });

        this.carregandoRemarcacao = false;

        this.agendamentoService.sugerirDatasRemarcacao(id).subscribe({
          next: (datas) => {
            this.sugestoesRemarcacao = datas;
          },
          error: () => {
            this.sugestoesRemarcacao = [];
          },
        });
      },
      error: (erro) => {
        console.error('Erro ao carregar agendamento original', erro);
        this.erroGeral =
          'Não foi possível carregar o agendamento original para remarcação.';
        this.carregandoRemarcacao = false;
      },
    });
  }

  onProfissionalOuDataAlterado(): void {
    this.vagas = null;
    this.turnoSelecionado = null;
    this.limparHorario();
    this.erroGeral = null;

    if (!this.profissionalSelecionadoId || !this.dataSelecionada) {
      return;
    }

    this.consultandoVagas = true;

    this.agendamentoService
      .consultarVagas(this.profissionalSelecionadoId, this.dataSelecionada)
      .pipe(
        finalize(() => {
          this.consultandoVagas = false;
        }),
      )
      .subscribe({
        next: (vagas) => {
          this.vagas = vagas;
        },
        error: (erro) => {
          console.error('Erro ao consultar vagas', erro);
          this.erroGeral = 'Não foi possível consultar as vagas disponíveis.';
        },
      });
  }

  selecionarDataSugerida(data: string): void {
    this.dataSelecionada = data;
    this.onProfissionalOuDataAlterado();
  }

  selecionarTurno(turno: string): void {
    if (
      this.vagasDoTurno(turno) <= 0 ||
      !this.profissionalSelecionadoId ||
      !this.dataSelecionada
    ) {
      return;
    }

    this.turnoSelecionado = turno;
    this.limparHorario();

    this.horarioConsulta$.next({
      usuarioId: this.profissionalSelecionadoId,
      data: this.dataSelecionada,
      turno,
    });
  }

  selecionarHorario(horario: HorarioDisponivel): void {
    if (!horario.disponivel) {
      return;
    }

    this.horaAtendimento = horario.hora.slice(0, 5);
  }

  private limparHorario(): void {
    this.horaAtendimento = '';
    this.horarios = [];
    this.horariosPayload = null;
    this.erroHorarios = null;
  }

  vagasDoTurno(turno: string): number {
    if (!this.vagas) {
      return 0;
    }

    return turno === 'MANHA' ? this.vagas.MANHA : this.vagas.TARDE;
  }

  get podeConfirmar(): boolean {
    return !!(
      this.pacienteSelecionado &&
      this.profissionalSelecionadoId &&
      this.dataSelecionada &&
      this.turnoSelecionado &&
      this.horaAtendimento &&
      !this.salvando
    );
  }

  confirmarAgendamento(): void {
    if (!this.podeConfirmar) {
      return;
    }

    this.salvando = true;
    this.erroGeral = null;
    this.errosPorCampo = {};

    const payload: NovoAgendamentoPayload = {
      usuarioId: this.profissionalSelecionadoId!,
      pacienteId: this.pacienteSelecionado!.idPublico!,
      dataAgendamento: this.dataSelecionada,
      turnoAgendamento: this.turnoSelecionado!,
      horaAtendimento: this.horaAtendimento,
      agendamentoOriginalId: this.agendamentoOriginalId ?? undefined,
    };

    this.agendamentoService.criarAgendamento(payload).subscribe({
      next: () => {
        this.salvando = false;
        this.router.navigate(['/agenda'], {
          queryParams: {
            data: this.dataSelecionada,
          },
        });
      },
      error: (erro: HttpErrorResponse) => {
        this.salvando = false;
        this.tratarErro(erro);
      },
    });
  }

  private tratarErro(erro: HttpErrorResponse): void {
    if (!erro.error) {
      this.erroGeral = 'Não foi possível conectar ao servidor. Tente novamente.';
      return;
    }

    if (erro.status === 422 && Array.isArray(erro.error.errors)) {
      const validationError = erro.error as ValidationError;

      validationError.errors.forEach((campo) => {
        this.errosPorCampo[campo.fieldName] = campo.message;
      });

      this.erroGeral = validationError.message;
      return;
    }

    this.erroGeral =
      (erro.error as StandardError)?.message ||
      'Não foi possível criar o agendamento.';
  }

  cancelar(): void {
    this.router.navigate(['/agenda']);
  }

  profissionalSelecionado(): ProfissionalPayload | undefined {
    return this.profissionais.find(
      (profissional) => profissional.idPublico === this.profissionalSelecionadoId,
    );
  }

  labelEnum(valor?: string): string {
    if (!valor) {
      return 'Não selecionado';
    }

    return valor
      .replaceAll('_', ' ')
      .toLowerCase()
      .replace(/\b\w/g, (letra) => letra.toUpperCase());
  }

  formatarCpf(cpf?: string): string {
    const numeros = cpf?.replace(/\D/g, '') ?? '';

    if (numeros.length !== 11) {
      return 'Não informado';
    }

    return numeros.replace(
      /(\d{3})(\d{3})(\d{3})(\d{2})/,
      '$1.$2.$3-$4',
    );
  }

  idade(data?: string): number | null {
    if (!data) {
      return null;
    }

    const hoje = new Date();
    const nascimento = new Date(`${data}T00:00:00`);

    let idade = hoje.getFullYear() - nascimento.getFullYear();

    const aniversarioNesteAno = new Date(
      hoje.getFullYear(),
      nascimento.getMonth(),
      nascimento.getDate(),
    );

    if (hoje < aniversarioNesteAno) {
      idade--;
    }

    return idade;
  }

  formatarData(data?: string): string {
    if (!data) {
      return 'Não selecionado';
    }

    return new Intl.DateTimeFormat('pt-BR', {
      dateStyle: 'long',
    }).format(new Date(`${data}T00:00:00`));
  }

  mensagemHorarios(): string {
    const motivo = this.horariosPayload?.motivoIndisponibilidade;

    if (motivo === 'AGENDA_BLOQUEADA') {
      return 'A agenda do profissional está bloqueada nesta data.';
    }

    if (motivo === 'TURNO_NAO_CONFIGURADO') {
      return 'Este profissional não possui disponibilidade neste turno.';
    }

    if (motivo === 'CAPACIDADE_ESGOTADA') {
      return 'Não há mais vagas disponíveis neste turno.';
    }

    return 'Nenhum horário disponível para este turno.';
  }

  private dataLocalHoje(): string {
    const data = new Date();
    const doisDigitos = (valor: number) => String(valor).padStart(2, '0');

    return `${data.getFullYear()}-${doisDigitos(data.getMonth() + 1)}-${doisDigitos(
      data.getDate(),
    )}`;
  }
}
