import { CommonModule } from "@angular/common";
import { HttpErrorResponse } from "@angular/common/http";
import { Component, OnDestroy, OnInit, inject } from "@angular/core";
import { FormBuilder, ReactiveFormsModule, Validators } from "@angular/forms";
import { ActivatedRoute, Router, RouterLink } from "@angular/router";
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
} from "rxjs";
import {
  CriarGrupoPayload,
  GrupoTerapeuticoService,
  RecorrenciaGrupo,
} from "../../services/grupo-terapeutico-service";
import {
  PacientePayload,
  PacienteService,
} from "../../services/paciente/paciente-service";
import {
  ProfissionalPayload,
  ProfissionalService,
} from "../../services/profissional/profissional-service";

@Component({
  selector: "app-agendar-grupo-terapeutico",
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: "./agendar-grupo-terapeutico.html",
  styleUrl: "./agendar-grupo-terapeutico.css",
})
export class AgendarGrupoTerapeutico implements OnInit, OnDestroy {
  private readonly fb = inject(FormBuilder);

  readonly recorrencias: { valor: RecorrenciaGrupo; label: string }[] = [
    { valor: "UNICA", label: "Única" },
    { valor: "SEMANAL", label: "Semanal" },
    { valor: "QUINZENAL", label: "Quinzenal" },
    { valor: "MENSAL", label: "Mensal" },
  ];

  readonly dataMinima = this.hoje();

  readonly form = this.fb.nonNullable.group({
    tema: ["", [Validators.required, Validators.maxLength(150)]],
    coordenadorId: ["", Validators.required],
    dataPrimeiraSessao: ["", Validators.required],
    horario: ["", Validators.required],
    recorrencia: ["UNICA" as RecorrenciaGrupo, Validators.required],
    dataFimRecorrencia: [""],
  });

  profissionais: ProfissionalPayload[] = [];
  participantes: PacientePayload[] = [];
  resultados: PacientePayload[] = [];
  pacienteSelecionado: PacientePayload | null = null;
  termoPesquisa = "";
  buscando = false;
  pesquisaRealizada = false;
  enviando = false;
  erroGeral = "";
  mensagemPesquisa = "";
  modoEdicao = false;
  grupoId: number | null = null;
  grupoVersion = 0;
  grupoIniciado = false;

  private readonly pesquisa$ = new Subject<string>();
  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly grupos: GrupoTerapeuticoService,
    private readonly pacientes: PacienteService,
    private readonly profissionaisService: ProfissionalService,
    private readonly router: Router,
    private readonly route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    this.grupoId = Number(this.route.snapshot.paramMap.get('grupoId')) || null;
    this.modoEdicao = this.grupoId !== null;
    this.carregarProfissionais();
    this.configurarPesquisa();
    this.configurarRecorrencia();
    if (this.modoEdicao) this.carregarGrupo();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get coordenadorNome(): string {
    return (
      this.profissionais.find(
        (p) => p.idPublico === this.form.controls.coordenadorId.value
      )?.nome || "Não selecionado"
    );
  }

  get recorrenciaLabel(): string {
    return (
      this.recorrencias.find(
        (r) => r.valor === this.form.controls.recorrencia.value
      )?.label || "Não definida"
    );
  }

  get primeiraSessaoResumo(): string {
    const d = this.form.controls.dataPrimeiraSessao.value;
    const h = this.form.controls.horario.value;
    return d ? `${this.formatarData(d)}${h ? " • " + h : ""}` : "Não definida";
  }

  get recorrenciaEhUnica(): boolean {
    return this.form.controls.recorrencia.value === "UNICA";
  }

  get fimRecorrenciaResumo(): string {
    if (this.recorrenciaEhUnica) return "Não se aplica";
    const data = this.form.controls.dataFimRecorrencia.value;
    return data ? this.formatarData(data) : "Não definido";
  }

  campoInvalido(nome: keyof typeof this.form.controls): boolean {
    const c = this.form.controls[nome];
    return c.invalid && (c.dirty || c.touched);
  }

  selecionarRecorrencia(valor: RecorrenciaGrupo): void {
    this.form.controls.recorrencia.setValue(valor);
    this.form.controls.recorrencia.markAsTouched();
  }

  pesquisar(termo: string): void {
    this.termoPesquisa = termo;
    this.pacienteSelecionado = null;
    this.mensagemPesquisa = "";
    this.pesquisa$.next(termo);
  }

  selecionarPaciente(p: PacientePayload): void {
    this.pacienteSelecionado = p;
    this.termoPesquisa = p.nome;
    this.resultados = [];
    this.mensagemPesquisa =
      p.statusPaciente !== "ATIVO"
        ? "Este paciente está inativo e não pode ser incluído."
        : "";
  }

  adicionarPaciente(): void {
    const p = this.pacienteSelecionado;
    if (!p?.idPublico) {
      this.mensagemPesquisa = "Selecione um paciente nos resultados da busca.";
      return;
    }
    if (p.statusPaciente !== "ATIVO") {
      this.mensagemPesquisa = "Somente pacientes ativos podem ser incluídos.";
      return;
    }
    if (this.participantes.some((item) => item.idPublico === p.idPublico)) {
      this.mensagemPesquisa = "Este paciente já foi adicionado.";
      return;
    }
    this.participantes = [...this.participantes, p];
    this.termoPesquisa = "";
    this.pacienteSelecionado = null;
    this.mensagemPesquisa = "";
    this.pesquisaRealizada = false;
  }

  removerPaciente(id?: string): void {
    this.participantes = this.participantes.filter((p) => p.idPublico !== id);
  }

  submit(): void {
    this.erroGeral = "";
    this.validarPeriodoRecorrencia();
    this.form.markAllAsTouched();

    if (this.form.invalid) {
      this.erroGeral = "Revise os campos obrigatórios antes de confirmar.";
      return;
    }

    if (!this.modoEdicao && this.form.controls.dataPrimeiraSessao.value < this.dataMinima) {
      this.form.controls.dataPrimeiraSessao.setErrors({ passada: true });
      return;
    }

    const value = this.form.getRawValue();
    const payload: CriarGrupoPayload = {
      tema: value.tema.trim(),
      coordenadorId: value.coordenadorId,
      recorrencia: value.recorrencia,
      dataPrimeiraSessao: value.dataPrimeiraSessao,
      dataFimRecorrencia:
        value.recorrencia === "UNICA" ? null : value.dataFimRecorrencia,
      horario: value.horario,
      participantesIds: this.participantes
        .map((p) => p.idPublico!)
        .filter(Boolean),
    };

    this.enviando = true;
    const request$ = this.modoEdicao ? this.grupos.atualizarGrupo(this.grupoId!, {
      tema: payload.tema, coordenadorId: payload.coordenadorId, recorrencia: payload.recorrencia,
      dataPrimeiraSessao: payload.dataPrimeiraSessao, dataFimRecorrencia: payload.dataFimRecorrencia,
      horario: payload.horario, version: this.grupoVersion,
    }) : this.grupos.criar(payload);
    request$
      .pipe(finalize(() => (this.enviando = false)))
      .subscribe({
        next: () => {
          const sessao = this.route.snapshot.queryParamMap.get('sessao');
          const data = this.route.snapshot.queryParamMap.get('data');
          return this.modoEdicao && sessao ? this.router.navigate(['/grupos-terapeuticos/detalhes', this.grupoId, 'sessao', sessao], { queryParams: { data } })
            : this.router.navigate(["/grupos-terapeuticos"], { queryParams: { data: value.dataPrimeiraSessao } });
        },
        error: (e: HttpErrorResponse) => this.tratarErro(e),
      });
  }

  private carregarGrupo(): void {
    this.grupos.buscarGrupo(this.grupoId!).subscribe({ next: g => {
      this.grupoVersion = g.version; this.grupoIniciado = g.iniciado;
      this.form.patchValue({ tema: g.tema, coordenadorId: g.coordenadorId, dataPrimeiraSessao: g.dataPrimeiraSessao,
        horario: g.horarioPadrao.slice(0,5), recorrencia: g.recorrencia, dataFimRecorrencia: g.dataFimRecorrencia ?? '' });
      if (g.iniciado) ['dataPrimeiraSessao','horario','recorrencia','dataFimRecorrencia'].forEach(c => this.form.get(c)?.disable());
    }, error: e => this.tratarErro(e) });
  }

  private configurarRecorrencia(): void {
    this.form.controls.recorrencia.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe((recorrencia) => {
        const fim = this.form.controls.dataFimRecorrencia;

        if (recorrencia === "UNICA") {
          fim.clearValidators();
          fim.setValue("", { emitEvent: false });
          fim.setErrors(null);
        } else {
          fim.setValidators(Validators.required);
        }

        fim.updateValueAndValidity({ emitEvent: false });
        this.validarPeriodoRecorrencia();
      });

    this.form.controls.dataPrimeiraSessao.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.validarPeriodoRecorrencia());

    this.form.controls.dataFimRecorrencia.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.validarPeriodoRecorrencia());
  }

  private validarPeriodoRecorrencia(): void {
    const recorrencia = this.form.controls.recorrencia.value;
    const primeira = this.form.controls.dataPrimeiraSessao.value;
    const fim = this.form.controls.dataFimRecorrencia;

    if (recorrencia === "UNICA") {
      fim.setErrors(null);
      return;
    }

    const valorFim = fim.value;

    if (!valorFim) {
      fim.setErrors({ required: true });
      return;
    }

    if (primeira && valorFim < primeira) {
      fim.setErrors({ anteriorPrimeiraSessao: true });
      return;
    }

    fim.setErrors(null);
  }

  private carregarProfissionais(): void {
    this.profissionaisService.listarParaSelecao().subscribe({
      next: (p) => (this.profissionais = p),
      error: () =>
        (this.erroGeral = "Não foi possível carregar os coordenadores."),
    });
  }

  private configurarPesquisa(): void {
    this.pesquisa$
      .pipe(
        map((t) => t.trim()),
        debounceTime(350),
        distinctUntilChanged(),
        switchMap((termo) => {
          const digitos = termo.replace(/\D/g, "");
          const temLetras = /[A-Za-zÀ-ÿ]/.test(termo);
          if (
            (temLetras && termo.length < 3) ||
            (!temLetras && ![11, 15].includes(digitos.length))
          ) {
            this.buscando = false;
            this.pesquisaRealizada = false;
            return of<PacientePayload[]>([]);
          }
          this.buscando = true;
          this.pesquisaRealizada = false;
          const req = temLetras
            ? this.pacientes.buscarPorNome(termo)
            : digitos.length === 11
            ? this.pacientes.buscarPorCpf(digitos).pipe(map((p) => [p]))
            : this.pacientes.buscarPorCns(digitos).pipe(map((p) => [p]));
          return req.pipe(
            catchError(() => of<PacientePayload[]>([])),
            finalize(() => {
              this.buscando = false;
              this.pesquisaRealizada = true;
            })
          );
        }),
        takeUntil(this.destroy$)
      )
      .subscribe((r) => (this.resultados = r));
  }

  private tratarErro(e: HttpErrorResponse): void {
    const mensagens: Record<number, string> = {
      400: "Há dados inválidos no formulário.",
      403: "Você não possui permissão para agendar este grupo.",
      404: "Coordenador ou paciente não encontrado.",
      409: "Um participante já está inscrito em outro grupo na mesma data e horário.",
      422: "Não foi possível validar os dados informados.",
    };
    this.erroGeral =
      e.error?.message ||
      mensagens[e.status] ||
      "Não foi possível agendar o grupo. Tente novamente.";
  }

  private formatarData(data: string): string {
    const [a, m, d] = data.split("-").map(Number);
    return new Intl.DateTimeFormat("pt-BR").format(new Date(a, m - 1, d));
  }

  private hoje(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(
      2,
      "0"
    )}-${String(d.getDate()).padStart(2, "0")}`;
  }

  documento(p: PacientePayload): string {
    const cpf = (p.cpf || "").replace(/\D/g, "");
    if (cpf.length === 11)
      return `CPF •••.${cpf.slice(3, 6)}.${cpf.slice(6, 9)}-••`;
    const cns = (p.cns || "").replace(/\D/g, "");
    return `CNS •••••••••••${cns.slice(-4)}`;
  }
}
