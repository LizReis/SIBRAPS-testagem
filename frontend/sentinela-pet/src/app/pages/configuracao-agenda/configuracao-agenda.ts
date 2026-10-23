import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, HostListener, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { catchError, finalize, forkJoin, Observable, of } from 'rxjs';


import { BloqueioAgendaDTO, BloqueioAgendaService } from '../../services/bloqueio-agenda-service';
import {
  DisponibilidadeExcecaoDTO,
  DisponibilidadeExcecaoService,
} from '../../services/disponibilidade-excecao-service';
import {
  DisponibilidadeDTO,
  DisponibilidadeService,
  StandardError,
} from '../../services/disponibilidade-service';
import {
  ProfissionalPayload,
  ProfissionalService,
} from '../../services/profissional/profissional-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';

type Aba = 'horarios' | 'bloqueios' | 'excecoes';
type Modal = 'confirmacao' | null;

type AcaoExclusao = {
  tipo: Aba;
  id: number;
};

type DiaSemanaOption = {
  valor: string;
  label: string;
  curto: string;
};

@Component({
  selector: 'app-configuracao-agenda',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './configuracao-agenda.html',
  styleUrl: './configuracao-agenda.css',
})
export class ConfiguracaoAgenda implements OnInit {
  readonly diasSemana: DiaSemanaOption[] = [
    { valor: 'MONDAY', label: 'Segunda-feira', curto: 'SEG' },
    { valor: 'TUESDAY', label: 'Terça-feira', curto: 'TER' },
    { valor: 'WEDNESDAY', label: 'Quarta-feira', curto: 'QUA' },
    { valor: 'THURSDAY', label: 'Quinta-feira', curto: 'QUI' },
    { valor: 'FRIDAY', label: 'Sexta-feira', curto: 'SEX' },
    { valor: 'SATURDAY', label: 'Sábado', curto: 'SÁB' },
    { valor: 'SUNDAY', label: 'Domingo', curto: 'DOM' },
  ];

  readonly turnos = ['MANHA', 'TARDE'];

  abaAtiva: Aba = 'horarios';
  modal: Modal = null;

  isAdmin = false;
  isRecepcao = false;
  isProfissional = false;
  carregandoUsuarioLogado = true;
  carregandoDados = false;
  salvandoHorario = false;
  salvandoBloqueio = false;
  salvandoExcecao = false;
  salvandoExclusao = false;

  nomeUsuario = '';
  profissionais: ProfissionalPayload[] = [];
  profissionalSelecionadoId: string | null = null;

  disponibilidades: DisponibilidadeDTO[] = [];
  bloqueios: BloqueioAgendaDTO[] = [];
  excecoes: DisponibilidadeExcecaoDTO[] = [];

  erroGeral: string | null = null;
  sucesso: string | null = null;
  erroHorario: string | null = null;
  erroBloqueio: string | null = null;
  erroExcecao: string | null = null;
  erroModal: string | null = null;

  disponibilidadeForm: DisponibilidadeDTO = {
    diaSemana: '',
    turno: '',
    capacidade: 1,
  };

  bloqueioForm: BloqueioAgendaDTO = {
    dataInicio: '',
    dataFim: '',
    motivoBloqueio: '',
  };

  excecaoForm: DisponibilidadeExcecaoDTO = {
    data: '',
    turno: '',
    capacidade: 1,
  };

  acaoExclusao: AcaoExclusao | null = null;
  elementoOrigem: HTMLElement | null = null;

  constructor(
    private readonly usuarioLogadoService: UsuarioLogadoService,
    private readonly profissionalService: ProfissionalService,
    private readonly disponibilidadeService: DisponibilidadeService,
    private readonly bloqueioAgendaService: BloqueioAgendaService,
    private readonly disponibilidadeExcecaoService: DisponibilidadeExcecaoService,
  ) {}

  ngOnInit(): void {
    this.usuarioLogadoService.obterUsuarioLogado().subscribe({
      next: (usuario) => {
        this.isAdmin = usuario.tipoUsuario === 'ADMINISTRADOR';
        this.isRecepcao = usuario.tipoUsuario === 'RECEPCAO';
        this.isProfissional = usuario.tipoUsuario === 'PROFISSIONAL';
        this.nomeUsuario = usuario.nome;
        this.carregandoUsuarioLogado = false;

        if (this.isAdmin || this.isRecepcao) {
          this.carregarProfissionais();
        } else if (this.isProfissional) {
          this.carregarDados();
        }
      },
      error: () => {
        this.carregandoUsuarioLogado = false;
        this.erroGeral = 'Não foi possível identificar o usuário logado.';
      },
    });
  }

  carregarProfissionais(): void {
    this.profissionalService.listarParaSelecao().subscribe({
      next: (profissionais) => {
        this.profissionais = profissionais;
      },
      error: () => {
        this.erroGeral = 'Não foi possível carregar a lista de profissionais.';
      },
    });
  }

  onProfissionalSelecionado(): void {
    this.limparDados();

    if (this.profissionalSelecionadoId) {
      this.carregarDados(this.profissionalSelecionadoId);
    }
  }

  limparDados(): void {
    this.disponibilidades = [];
    this.bloqueios = [];
    this.excecoes = [];
    this.erroGeral = null;
    this.sucesso = null;
  }

  carregarDados(usuarioId?: string): void {
    this.carregandoDados = true;
    this.erroGeral = null;

    const erros: string[] = [];

    forkJoin({
      disponibilidades: this.disponibilidadeService
        .listar(usuarioId)
        .pipe(
          catchError((erro) =>
            this.recuperarFalhaDeCarregamento<DisponibilidadeDTO>(erro, 'os horários', erros),
          ),
        ),

      bloqueios: this.bloqueioAgendaService
        .listar(usuarioId)
        .pipe(
          catchError((erro) =>
            this.recuperarFalhaDeCarregamento<BloqueioAgendaDTO>(erro, 'os bloqueios', erros),
          ),
        ),

      
      excecoes: this.disponibilidadeExcecaoService
        .listar(usuarioId)
        .pipe(
          catchError((erro) =>
            this.recuperarFalhaDeCarregamento<DisponibilidadeExcecaoDTO>(
              erro,
              'as exceções',
              erros,
            ),
          ),
        ),
    })
      .pipe(finalize(() => (this.carregandoDados = false)))
      .subscribe({
        next: (dados) => {
          this.disponibilidades = this.ordenarHorarios(dados.disponibilidades);
          this.bloqueios = dados.bloqueios;
          this.excecoes = [...dados.excecoes].sort((a, b) => a.data.localeCompare(b.data));
          this.erroGeral = erros.length ? erros.join(' ') : null;
        },
      });
  }

  get usuarioIdAtivo(): string | undefined {
    return this.isAdmin || this.isRecepcao
      ? (this.profissionalSelecionadoId ?? undefined)
      : undefined;
  }

  get podeGerenciar(): boolean {
    const possuiProfissionalAlvo = this.isAdmin || this.isRecepcao
      ? !!this.profissionalSelecionadoId
      : this.isProfissional;

    return possuiProfissionalAlvo && !this.carregandoDados;
  }

  selecionarAba(aba: Aba): void {
    this.abaAtiva = aba;
  }

  navegarAbas(event: KeyboardEvent): void {
    const abas: Aba[] = ['horarios', 'bloqueios', 'excecoes'];
    const indiceAtual = abas.indexOf(this.abaAtiva);

    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') {
      return;
    }

    event.preventDefault();

    const deslocamento = event.key === 'ArrowRight' ? 1 : -1;
    const proximoIndice = (indiceAtual + deslocamento + abas.length) % abas.length

    this.abaAtiva = abas[proximoIndice];

    setTimeout(() => {
      document.getElementById(`tab-${this.abaAtiva}`)?.focus();
    });
  }

  abrirHorario(disponibilidade?: DisponibilidadeDTO): void {
    this.erroHorario = null;
    this.sucesso = null;
    this.disponibilidadeForm = disponibilidade
      ? { ...disponibilidade }
      : {
        diaSemana: '',
          turno: '',
          capacidade: 1,
        };
    this.rolarParaFormulario('form-horario', 'capacidade-horario');
  }

  abrirBloqueio(bloqueio?: BloqueioAgendaDTO): void {
    this.erroBloqueio = null;
    this.sucesso = null;
    this.bloqueioForm = bloqueio
      ? { ...bloqueio }
      : {
        dataInicio: '',
          dataFim: '',
          motivoBloqueio: '',
        };
    this.rolarParaFormulario('form-bloqueio', 'data-inicio-bloqueio');
  }

  abrirExcecao(excecao?: DisponibilidadeExcecaoDTO): void {
    this.erroExcecao = null;
    this.sucesso = null;
    this.excecaoForm = excecao
      ? { ...excecao }
      : {
        data: '',
          turno: '',
          capacidade: 1,
        };
    this.rolarParaFormulario('form-excecao', 'capacidade-excecao');
  }

  cancelarEdicaoHorario(): void {
    this.abrirHorario();
  }

  cancelarEdicaoBloqueio(): void {
    this.abrirBloqueio();
  }

  cancelarEdicaoExcecao(): void {
    this.abrirExcecao();
  }

  private rolarParaFormulario(formularioId: string, campoEdicaoId: string): void {
    setTimeout(() => {
      const formulario = document.getElementById(formularioId);
      formulario?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      const primeiroEditavel = formulario?.querySelector<HTMLElement>(
        'input:not([readonly]):not([disabled]), select:not([disabled])',
      );
      (document.getElementById(campoEdicaoId) ?? primeiroEditavel)?.focus();
    });
  }

  confirmarExclusao(tipo: Aba, id: number | undefined, origem?: Event): void {
    if (id == null) {
      return;
    }

    this.prepararModal(origem);
    this.acaoExclusao = { tipo, id };
    this.modal = 'confirmacao';
  }

  prepararModal(origem?: Event): void {
    this.elementoOrigem = (origem?.currentTarget as HTMLElement | null) ?? null;
    this.erroModal = null;
    this.sucesso = null;

    setTimeout(() => {
      document
        .querySelector<HTMLElement>(
          '.modal-card input:not([readonly]), .modal-card select:not([disabled]), .modal-card button',
        )
        ?.focus();
    });
  }

  fecharModal(): void {
    if (this.salvandoExclusao) {
      return;
    }

    this.modal = null;
    this.erroModal = null;
    this.acaoExclusao = null;

    setTimeout(() => this.elementoOrigem?.focus());
  }

  @HostListener('document:keydown.escape')
  aoEscape(): void {
    if (this.modal) {
      this.fecharModal();
    }
  }

  prenderFoco(event: KeyboardEvent): void {
    if (event.key !== 'Tab') {
      return;
    }

    const itens = Array.from(
      document.querySelectorAll<HTMLElement>(
        '.modal-card button:not([disabled]), .modal-card input:not([disabled]), .modal-card select:not([disabled])',
      ),
    );

    if (!itens.length) {
      return;
    }

    const primeiro = itens[0];
    const ultimo = itens[itens.length - 1];

    if (event.shiftKey && document.activeElement === primeiro) {
      event.preventDefault();
      ultimo.focus();
    } else if (!event.shiftKey && document.activeElement === ultimo) {
      event.preventDefault();
      primeiro.focus();
    }
  }


  salvarHorario(): void {
    if (this.salvandoHorario) {
      return;
    }

    const formulario = this.disponibilidadeForm;
    const capacidade = Number(formulario.capacidade);

    if (!this.validarProfissionalSelecionado('horarios')) {
      return;
    }

    if (
      !formulario.diaSemana ||
      !formulario.turno ||
      !Number.isInteger(capacidade) ||
      capacidade < 1
    ) {
      this.erroHorario = 'Informe dia, turno e uma quantidade inteira de vagas maior que zero.';
      return;
    }

    const payload: DisponibilidadeDTO = {
      ...formulario,
      capacidade,
      usuarioId: this.usuarioIdAtivo,
    };

    const mensagem = formulario.id
      ? 'Horário atualizado com sucesso.'
      : 'Horário adicionado com sucesso.';

    this.executar(this.disponibilidadeService.salvar(payload), mensagem, 'horarios');
  }

  salvarBloqueio(): void {
    if (this.salvandoBloqueio) {
      return;
    }

    const formulario = this.bloqueioForm;
    const motivo = formulario.motivoBloqueio?.trim();

    if (!this.validarProfissionalSelecionado('bloqueios')) {
      return;
    }

    if (!formulario.dataInicio || !formulario.dataFim || !motivo) {
      this.erroBloqueio = 'Preencha as datas e o motivo do bloqueio.';
      return;
    }

    if (formulario.dataFim < formulario.dataInicio) {
      this.erroBloqueio = 'A data final não pode ser anterior à data inicial.';
      return;
    }

    const mensagem = formulario.id
      ? 'Bloqueio atualizado com sucesso.'
      : 'Bloqueio criado com sucesso.';

    this.executar(
      this.bloqueioAgendaService.salvar({
        ...formulario,
        motivoBloqueio: motivo,
        usuarioId: this.usuarioIdAtivo,
      }),
      mensagem,
      'bloqueios',
    );
  }

  salvarExcecao(): void {
    if (this.salvandoExcecao) {
      return;
    }

    const formulario = this.excecaoForm;
    const capacidade = Number(formulario.capacidade);

    if (!this.validarProfissionalSelecionado('excecoes')) {
      return;
    }

    if (
      !formulario.data ||
      !formulario.turno ||
      formulario.capacidade == null ||
      !Number.isInteger(capacidade) ||
      capacidade < 0
    ) {
      this.erroExcecao = 'Informe data, turno e uma capacidade inteira igual ou maior que zero.';
      return;
    }

    const payload: DisponibilidadeExcecaoDTO = {
      ...formulario,
      capacidade,
      usuarioId: this.usuarioIdAtivo,
    };

    const mensagem = formulario.id
      ? 'Exceção atualizada com sucesso.'
      : 'Exceção criada com sucesso.';

    this.executar(this.disponibilidadeExcecaoService.salvar(payload), mensagem, 'excecoes');
  }

  executar<T>(observable: Observable<T>, mensagem: string, tipo?: Aba): void {
    if (this.estaSalvando(tipo)) {
      return;
    }

    this.definirSalvando(tipo, true);
    this.definirErro(tipo, null);

    observable.pipe(finalize(() => this.definirSalvando(tipo, false))).subscribe({
      next: () => {
        this.modal = null;
        this.acaoExclusao = null;
        this.sucesso = mensagem;
        this.limparFormulario(tipo);
        this.carregarDados(this.usuarioIdAtivo);

        setTimeout(() => this.elementoOrigem?.focus());
      },
      error: (erro: HttpErrorResponse) => {
        this.definirErro(
          tipo,
          this.extrairMensagemErro(erro, 'Não foi possível concluir a operação.'),
        );
      },
    });
  }

  excluir(): void {
    if (!this.acaoExclusao || this.salvandoExclusao) {
      return;
    }

    const { tipo, id } = this.acaoExclusao;

    let operacao: Observable<unknown>;
    let mensagem: string;

    if (tipo === 'horarios') {
      operacao = this.disponibilidadeService.remover(id);
      mensagem = 'Horário excluído com sucesso.';
    } else if (tipo === 'bloqueios') {
      operacao = this.bloqueioAgendaService.remover(id);
      mensagem = 'Bloqueio excluído com sucesso.';
    } else {
      operacao = this.disponibilidadeExcecaoService.remover(id);
      mensagem = 'Exceção excluída com sucesso.';
    }

    this.executar(operacao, mensagem);
  }

  capacidade(dia: string, turno: string): number | null {
    return (
      this.disponibilidades.find(
        (disponibilidade) => disponibilidade.diaSemana === dia && disponibilidade.turno === turno,
      )?.capacidade ?? null
    );
  }

  labelDiaSemana(valor: string): string {
    return this.diasSemana.find((dia) => dia.valor === valor)?.label ?? valor;
  }

  labelTurno(valor: string): string {
    return valor === 'MANHA' ? 'Manhã' : 'Tarde';
  }

  formatarData(data: string): string {
    if (!data) {
      return '-';
    }

    const [ano, mes, dia] = data.split('-');
    return `${dia}/${mes}/${ano}`;
  }

  periodo(bloqueio: BloqueioAgendaDTO): string {
    if (bloqueio.dataInicio === bloqueio.dataFim) {
      return this.formatarData(bloqueio.dataInicio);
    }

    return `${this.formatarData(bloqueio.dataInicio)} até ${this.formatarData(bloqueio.dataFim)}`;
  }

  private validarProfissionalSelecionado(tipo: Aba): boolean {
    if ((this.isAdmin || this.isRecepcao) && !this.profissionalSelecionadoId) {
      this.definirErro(tipo, 'Selecione um profissional antes de salvar.');
      return false;
    }

    return true;
  }

  private estaSalvando(tipo?: Aba): boolean {
    return tipo === 'horarios'
      ? this.salvandoHorario
      : tipo === 'bloqueios'
        ? this.salvandoBloqueio
        : tipo === 'excecoes'
          ? this.salvandoExcecao
          : this.salvandoExclusao;
  }

  private definirSalvando(tipo: Aba | undefined, valor: boolean): void {
    if (tipo === 'horarios') this.salvandoHorario = valor;
    else if (tipo === 'bloqueios') this.salvandoBloqueio = valor;
    else if (tipo === 'excecoes') this.salvandoExcecao = valor;
    else this.salvandoExclusao = valor;
  }

  private definirErro(tipo: Aba | undefined, mensagem: string | null): void {
    if (tipo === 'horarios') this.erroHorario = mensagem;
    else if (tipo === 'bloqueios') this.erroBloqueio = mensagem;
    else if (tipo === 'excecoes') this.erroExcecao = mensagem;
    else this.erroModal = mensagem;
  }

  private limparFormulario(tipo?: Aba): void {
    if (tipo === 'horarios') {
      this.disponibilidadeForm = {
        diaSemana: '',
        turno: '',
        capacidade: 1,
      };
    } else if (tipo === 'bloqueios') {
      this.bloqueioForm = {
        dataInicio: '',
        dataFim: '',
        motivoBloqueio: '',
      };
    } else if (tipo === 'excecoes') {
      this.excecaoForm = {
        data: '',
        turno: '',
        capacidade: 1,
      };
    }
  }

  private recuperarFalhaDeCarregamento<T>(
    erro: HttpErrorResponse,
    recurso: string,
    erros: string[],
  ): Observable<T[]> {
    console.error(`Erro ao carregar ${recurso}`, erro);
    erros.push(this.extrairMensagemErro(erro, `Não foi possível carregar ${recurso}.`));
    return of([] as T[]);
  }

  private ordenarHorarios(disponibilidades: DisponibilidadeDTO[]): DisponibilidadeDTO[] {
    const ordemTurnos = new Map<string, number>([
      ['MANHA', 0],
      ['TARDE', 1],
    ]);

    return [...disponibilidades].sort((a, b) => {
      const diaA = this.diasSemana.findIndex((dia) => dia.valor === a.diaSemana);
      const diaB = this.diasSemana.findIndex((dia) => dia.valor === b.diaSemana);

      if (diaA !== diaB) {
        return diaA - diaB;
      }

      return (ordemTurnos.get(a.turno) ?? 99) - (ordemTurnos.get(b.turno) ?? 99);
    });
  }

  private extrairMensagemErro(erro: HttpErrorResponse, mensagemPadrao: string): string {
    const corpo = erro.error as
      | (StandardError & { errors?: Array<{ message: string }> })
      | undefined;

    const mensagensValidacao = corpo?.errors
      ?.map((item) => item.message)
      .filter(Boolean)
      .join(' ');

    if (mensagensValidacao) {
      return mensagensValidacao;
    }

    if (corpo?.message) {
      return corpo.message;
    }

    if (erro.status === 0) {
      return 'Não foi possível conectar ao servidor.';
    }

    if (erro.status === 401) {
      return 'Sua sessão expirou. Entre novamente.';
    }

    if (erro.status === 403) {
      return 'Você não tem permissão para realizar esta operação.';
    }

    return mensagemPadrao;
  }
}
