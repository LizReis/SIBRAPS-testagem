import { Subscription } from 'rxjs';
import { podeCriarAgendamento } from '../../auth/permissoes';
import { interpretarErroHttp } from '../../services/erro-http';
import { contratoExperimental } from '../../services/configuracao-contrato';
import {
    CommonModule
} from '@angular/common';
import {
    HttpErrorResponse
} from '@angular/common/http';
import {
    ChangeDetectorRef,
    Component,
    HostListener,
    OnDestroy,
    OnInit
} from '@angular/core';
import {
    FormsModule
} from '@angular/forms';
import {
    Router,
    RouterLink
} from '@angular/router';

import {
    ProfissionalPayload,
    ProfissionalService
} from '../../services/profissional/profissional-service';
import {
    AgendamentoDTO,
    AgendamentoService,
    rotuloReserva,
    rotuloFrequencia,
} from '../../services/agendamento-service';
import {
    UsuarioLogadoDTO,
    UsuarioLogadoService
} from '../../services/usuario-logado-service';

type VisualizacaoAgenda = 'dia' | 'semana' | 'mes';

type DiaCalendario = {
    data: Date;iso: string;dia: number;foraDoMes: boolean;hoje: boolean;selecionado: boolean
};
type DiaSemana = {
    iso: string;data: Date;titulo: string;subtitulo: string;agendamentos: AgendamentoDTO[]
};
type DiaMes = DiaCalendario & {
    agendamentos: AgendamentoDTO[]
};
type StatusUi = {
    label: string;classe: string;patternClass: string;descricao ? : string
};
type BlocoHoraAgenda = {
    hora: number;label: string;agendamentos: AgendamentoDTO[]
};

@Component({
    selector: 'app-agenda',
    standalone: true,
    imports: [CommonModule, FormsModule, RouterLink],
    templateUrl: './agenda.html',
    styleUrl: './agenda.css',
})
export class Agenda implements OnInit, OnDestroy {
    private readonly horaInicialPadrao = 7;
    private readonly horaFinalPadrao = 18;
    private relogioId: ReturnType < typeof setInterval > | null = null;
    private ultimaConsulta = '';
    private consultaAgenda?: Subscription;

    dataSelecionada: string = this.formatarDataISO(new Date());
    mesCalendario = this.inicioDoMes(this.criarDataLocal(this.dataSelecionada));
    visualizacao: VisualizacaoAgenda = 'dia';
    visualizacoes: {
        valor: VisualizacaoAgenda;label: string
    } [] = [{
            valor: 'dia',
            label: 'Dia'
        },
        {
            valor: 'semana',
            label: 'Semana'
        },
        {
            valor: 'mes',
            label: 'Mês'
        },
    ];

    usuarioLogado: UsuarioLogadoDTO | null = null;

    podeFiltrarProfissional = false;
    podeConfigurarAgenda = false;
    podeCriarAgendamento = false;
    carregandoUsuarioLogado = true;
    carregandoProfissionais = false;

    profissionais: ProfissionalPayload[] = [];
    profissionalSelecionadoId: string | null = null;

    agendamentos: AgendamentoDTO[] = [];

    carregando = false;
    erroGeral: string | null = null;

    atualizandoId: number | null = null;
    menuAbertoId: number | null = null;
    horarioAtual = new Date();

    readonly diasSemanaCurtos = ['S', 'T', 'Q', 'Q', 'S', 'S', 'D'];
    readonly statusUi: Record < string, StatusUi > = {
        PRESENTE: {
            label: 'PRESENTE',
            classe: 'status-presente',
            patternClass: 'pattern-presente'
        },
        AGENDADO: {
            label: 'AGENDADO',
            classe: 'status-agendado',
            patternClass: 'pattern-agendado'
        },
        FALTOU: {
            label: 'FALTOU',
            classe: 'status-faltou',
            patternClass: 'pattern-faltou'
        },
        REMARCADO: {
            label: 'REMARCADO',
            classe: 'status-remarcado',
            patternClass: 'pattern-remarcado'
        },
        CANCELADO: {
            label: 'CANCELADO',
            classe: 'status-cancelado',
            patternClass: 'pattern-cancelado'
        },
        REMARCADO_ORIGEM: {
            label: 'AGENDAMENTO ANTERIOR',
            classe: 'status-remarcado-origem',
            patternClass: 'pattern-remarcado-origem',
            descricao: 'Este agendamento foi substituído por uma nova remarcação.'
        },
    };
    readonly situacoesResumo = ['AGENDADO', 'PRESENTE', 'FALTOU', 'REMARCADO', 'CANCELADO', 'REMARCADO_ORIGEM'];
    readonly situacoesLegenda = Object.keys(this.statusUi);

    constructor(
        private usuarioLogadoService: UsuarioLogadoService,
        private profissionalService: ProfissionalService,
        private agendamentoService: AgendamentoService,
        private router: Router,
        private readonly cdr: ChangeDetectorRef,
    ) {}

    ngOnInit(): void {
        this.iniciarRelogio();
        this.usuarioLogadoService.obterUsuarioLogado().subscribe({
            next: (usuario) => {
                this.usuarioLogado = usuario;
                this.podeFiltrarProfissional = usuario.tipoUsuario !== 'PROFISSIONAL';
                this.podeConfigurarAgenda = ['ADMINISTRADOR', 'RECEPCAO', 'PROFISSIONAL'].includes(usuario.tipoUsuario);
                this.podeCriarAgendamento = podeCriarAgendamento(usuario.tipoUsuario);
                this.carregandoUsuarioLogado = false;

                if (this.podeFiltrarProfissional) this.carregarProfissionais();
                this.carregarAgenda();
            },
            error: (erro) => {
                this.erroGeral = interpretarErroHttp(erro, { padrao: 'Não foi possível identificar o usuário logado.' }).mensagem;
                this.cdr.markForCheck();
                this.carregandoUsuarioLogado = false;
            },
        });
    }

    ngOnDestroy(): void {
        if (this.relogioId) clearInterval(this.relogioId);
        this.consultaAgenda?.unsubscribe();
    }

    @HostListener('document:click') fecharMenuAoClicarFora(): void {
        this.menuAbertoId = null;
    }
    @HostListener('document:keydown.escape') fecharMenuComEscape(): void {
        this.menuAbertoId = null;
    }

    carregarProfissionais(): void {
        this.carregandoProfissionais = true;
        this.profissionalService.listarParaSelecao().subscribe({
            next: (profissionais) => {
                this.profissionais = profissionais;
                this.carregandoProfissionais = false;
                this.cdr.markForCheck();
            },
            error: () => {
                this.carregandoProfissionais = false;
                this.cdr.markForCheck();
            },
        });
    }

    carregarAgenda(forcar = false): void {
        const {
            inicio,
            fim
        } = this.periodoAtual();
        const profissionalId = this.podeFiltrarProfissional ? (this.profissionalSelecionadoId ?? undefined) : undefined;
        const chave = `${this.visualizacao}|${inicio}|${fim}|${profissionalId ?? 'todos'}`;
        if (!forcar && chave === this.ultimaConsulta) return;
        this.consultaAgenda?.unsubscribe();
        this.ultimaConsulta = chave;
        this.agendaRecarregada = false;
        if (this.correcao) this.revisaoDesatualizada = true;
        this.agendamentos = [];
        this.carregando = true;
        this.erroGeral = null;
        this.menuAbertoId = null;
        this.consultaAgenda = this.agendamentoService.buscarAgendaPorPeriodo(inicio, fim, profissionalId).subscribe({
            next: (agendamentos) => {
                this.agendamentos = this.ordenar(agendamentos);
                this.agendaRecarregada = true;
                this.carregando = false;
                this.cdr.markForCheck();
            },
            error: (erro) => {
                this.agendamentos = [];
                this.ultimaConsulta = '';
                this.erroGeral = interpretarErroHttp(erro, { padrao: this.mensagemErroAgenda() }).mensagem;
                this.cdr.markForCheck();
                this.carregando = false;
            },
        });
    }

    alterarVisualizacao(v: VisualizacaoAgenda): void {
        this.visualizacao = v;
        this.mesCalendario = this.inicioDoMes(this.criarDataLocal(this.dataSelecionada));
        this.carregarAgenda();
    }
    onProfissionalAlterado(): void {
        this.carregarAgenda(true);
    }
    tentarNovamente(): void {
        this.carregarAgenda(true);
    }
    irParaHoje(): void {
        this.dataSelecionada = this.formatarDataISO(new Date());
        this.mesCalendario = this.inicioDoMes(new Date());
        this.carregarAgenda(true);
    }

    navegarPeriodo(offset: number): void {
        const data = this.criarDataLocal(this.dataSelecionada);
        if (this.visualizacao === 'dia') data.setDate(data.getDate() + offset);
        if (this.visualizacao === 'semana') data.setDate(data.getDate() + offset * 7);
        if (this.visualizacao === 'mes') data.setMonth(data.getMonth() + offset, 1);
        this.dataSelecionada = this.formatarDataISO(data);
        this.mesCalendario = this.inicioDoMes(data);
        this.carregarAgenda(true);
    }

    selecionarData(iso: string): void {
        this.dataSelecionada = iso;
        this.mesCalendario = this.inicioDoMes(this.criarDataLocal(iso));
        this.carregarAgenda(true);
    }
    selecionarDiaDoMes(iso: string): void {
        this.dataSelecionada = iso;
        this.visualizacao = 'dia';
        this.mesCalendario = this.inicioDoMes(this.criarDataLocal(iso));
        this.carregarAgenda(true);
    }
    mudarMesCalendario(offset: number): void {
        const d = new Date(this.mesCalendario);
        d.setMonth(d.getMonth() + offset, 1);
        this.mesCalendario = d;
    }

    get agendamentosDia(): AgendamentoDTO[] {
        return this.agendamentos.filter((a) => a.dataAgendamento === this.dataSelecionada);
    }
    get horaInicialVisivel(): number {
        return Math.min(this.horaInicialPadrao, ...this.agendamentosDia.map((a) => Math.floor(this.minutosDoHorario(a.horaAtendimento) / 60)));
    }
    get horaFinalVisivel(): number {
        return Math.max(this.horaFinalPadrao, ...this.agendamentosDia.map((a) => Math.floor(this.minutosDoHorario(a.horaAtendimento) / 60)));
    }
    get blocosHorariosDia(): BlocoHoraAgenda[] {
        const blocos = new Map < number,
            BlocoHoraAgenda > ();
        for (let hora = this.horaInicialVisivel; hora <= this.horaFinalVisivel; hora++) {
            blocos.set(hora, {
                hora,
                label: `${String(hora).padStart(2, '0')}:00`,
                agendamentos: []
            });
        }

        this.agendamentosDia.forEach((agendamento) => {
            const hora = Math.floor(this.minutosDoHorario(agendamento.horaAtendimento) / 60);
            const bloco = blocos.get(hora);
            if (bloco) bloco.agendamentos.push(agendamento);
        });

        return Array.from(blocos.values()).map((bloco) => ({
            ...bloco,
            agendamentos: [...bloco.agendamentos].sort((a, b) => this.minutosDoHorario(a.horaAtendimento) - this.minutosDoHorario(b.horaAtendimento) || a.id - b.id),
        }));
    }
    get horaAtualBloco(): number {
        return this.horarioAtual.getHours();
    }
    get posicaoIndicadorAtualPercentual(): number {
        return (this.horarioAtual.getMinutes() / 60) * 100;
    }
    get mostrarIndicadorAtual(): boolean {
        const hora = this.horaAtualBloco;
        return this.visualizacao === 'dia' && this.dataSelecionada === this.formatarDataISO(new Date()) && hora >= this.horaInicialVisivel && hora <= this.horaFinalVisivel;
    }
    get periodoTitulo(): string {
        const {
            inicio,
            fim
        } = this.periodoAtual();
        if (this.visualizacao === 'dia') return this.formatarDataLonga(this.criarDataLocal(inicio));
        if (this.visualizacao === 'semana') return `${this.formatarDataCurta(this.criarDataLocal(inicio))} a ${this.formatarDataCurta(this.criarDataLocal(fim))}`;
        return this.formatarMesAno(this.criarDataLocal(inicio));
    }
    get contagemTitulo(): string {
        const n = this.agendamentos.length;
        return `${n} atendimento${n === 1 ? '' : 's'} agendado${n === 1 ? '' : 's'}`;
    }
    get resumoTitulo(): string {
        return this.visualizacao === 'dia' ? 'Resumo do dia' : this.visualizacao === 'semana' ? 'Resumo da semana' : 'Resumo do mês';
    }
    get mensagemVazia(): string {
        return this.visualizacao === 'dia' ? 'Nenhum atendimento agendado para esta data.' : this.visualizacao === 'semana' ? 'Nenhum atendimento encontrado nesta semana.' : 'Nenhum atendimento encontrado neste mês.';
    }
    get calendario(): DiaCalendario[] {
        return this.montarGradeMensal(this.mesCalendario);
    }
    get diasDaSemana(): DiaSemana[] {
        const inicio = this.inicioDaSemana(this.criarDataLocal(this.dataSelecionada));
        return Array.from({
            length: 7
        }, (_, i) => {
            const d = new Date(inicio);
            d.setDate(d.getDate() + i);
            const iso = this.formatarDataISO(d);
            return {
                iso,
                data: d,
                titulo: d.toLocaleDateString('pt-BR', {
                    weekday: 'short'
                }).replace('.', ''),
                subtitulo: d.toLocaleDateString('pt-BR', {
                    day: '2-digit',
                    month: '2-digit'
                }),
                agendamentos: this.agendamentos.filter((a) => a.dataAgendamento === iso)
            };
        });
    }
    get gradeMes(): DiaMes[] {
        return this.montarGradeMensal(this.criarDataLocal(this.dataSelecionada)).map((d) => ({
            ...d,
            agendamentos: this.agendamentos.filter((a) => a.dataAgendamento === d.iso)
        }));
    }
    get resumoSituacoesCompleto(): {
        situacao: string;label: string;total: number
    } [] {
        const c = new Map < string,
            number > ();
        this.agendamentos.forEach((a) => c.set(a.situacaoAtendimento, (c.get(a.situacaoAtendimento) ?? 0) + 1));
        return this.situacoesResumo.map((situacao) => ({
            situacao,
            label: this.labelSituacao(situacao),
            total: c.get(situacao) ?? 0
        }));
    }

    get resumoCanonico(): { label: string; total: number }[] {
        const reservas = ['AGENDADA', 'REMARCADA', 'CANCELADA', 'SUBSTITUIDA', null] as const;
        const frequencias = ['NAO_REGISTRADA', 'PRESENTE', 'FALTOU', null] as const;
        return [
            ...reservas.map(situacao => ({
                label: `Reserva: ${rotuloReserva(situacao)}`,
                total: this.agendamentos.filter(a => (a.situacaoReserva ?? null) === situacao).length,
            })),
            ...frequencias.map(frequencia => ({
                label: `Frequência: ${rotuloFrequencia(frequencia)}`,
                total: this.agendamentos.filter(a => (a.frequenciaAssistencial ?? null) === frequencia).length,
            })),
        ];
    }

    readonly rotuloReserva = rotuloReserva;
    readonly rotuloFrequencia = rotuloFrequencia;

    podeRegistrarFrequencia(a: AgendamentoDTO): boolean {
        const u = this.usuarioLogado;
        const vigente = a.situacaoReserva === 'AGENDADA' || a.situacaoReserva === 'REMARCADA';
        return !!u && (u.tipoUsuario !== 'PROFISSIONAL' || u.idPublico === a.usuarioId)
            && a.dataAgendamento <= this.formatarDataISO(new Date())
            && !a.migracaoPendente
            && (contratoExperimental ? vigente && a.frequenciaAssistencial === 'NAO_REGISTRADA'
                : ['AGENDADO', 'REMARCADO'].includes(a.situacaoAtendimento));
    }
    podeCancelar(a: AgendamentoDTO): boolean {
        const u = this.usuarioLogado;
        return contratoExperimental && !a.migracaoPendente && !!u && (u.tipoUsuario !== 'PROFISSIONAL' || u.idPublico === a.usuarioId)
            && a.frequenciaAssistencial === 'NAO_REGISTRADA'
            && (a.situacaoReserva === 'AGENDADA' || a.situacaoReserva === 'REMARCADA');
    }
    cancelarReserva(a: AgendamentoDTO): void {
        if (this.podeCancelar(a)) this.marcarStatus(a, 'CANCELADO');
    }
    podeRegularizar(a: AgendamentoDTO): boolean {
        const u = this.usuarioLogado;
        return contratoExperimental && !a.migracaoPendente && a.situacaoReserva === 'CANCELADA' && a.frequenciaAssistencial === 'NAO_REGISTRADA'
            && !!u && (u.tipoUsuario === 'ADMINISTRADOR'
                || (u.tipoUsuario === 'PROFISSIONAL' && u.idPublico === a.usuarioId));
    }

    podeCorrigirFrequencia(a: AgendamentoDTO): boolean {
        return !a.migracaoPendente && !!this.usuarioLogado && ['ADMINISTRADOR', 'PROFISSIONAL'].includes(this.usuarioLogado.tipoUsuario)
            && (this.usuarioLogado.tipoUsuario !== 'PROFISSIONAL' || this.usuarioLogado.idPublico === a.usuarioId)
            && a.dataAgendamento <= this.formatarDataISO(new Date())
            && ['PRESENTE', 'FALTOU'].includes(contratoExperimental ? a.frequenciaAssistencial ?? '' : a.situacaoAtendimento);
    }
    podeRemarcar(a: AgendamentoDTO): boolean {
        const elegivel = contratoExperimental
            ? (a.situacaoReserva === 'AGENDADA' || a.situacaoReserva === 'REMARCADA')
                && (a.frequenciaAssistencial === 'NAO_REGISTRADA' || a.frequenciaAssistencial === 'FALTOU')
            : ['AGENDADO', 'REMARCADO', 'FALTOU'].includes(a.situacaoAtendimento);
        return this.podeCriarAgendamento && elegivel && !a.migracaoPendente && a.tipoAcompanhamento !== 'GRUPO_TERAPEUTICO';
    }
    possuiAcoes(a: AgendamentoDTO): boolean {
        return this.podeCancelar(a) || this.podeRegistrarFrequencia(a) || this.podeCorrigirFrequencia(a) || this.podeRemarcar(a) || this.podeRegularizar(a);
    }
    toggleMenu(event: Event, id: number): void {
        event.stopPropagation();
        this.menuAbertoId = this.menuAbertoId === id ? null : id;
    }
    registrarPresenca(a: AgendamentoDTO): void {
        if (this.podeRegistrarFrequencia(a)) this.marcarStatus(a, 'PRESENTE');
    }
    registrarFalta(a: AgendamentoDTO): void {
        if (this.podeRegistrarFrequencia(a)) this.marcarStatus(a, 'FALTOU');
    }
    readonly contratoExperimental = contratoExperimental;
    correcao?: AgendamentoDTO;
    revisaoDesatualizada = false;
    private agendaRecarregada = true;
    justificativa = '';
    tipoRegularizacao: 'RECUPERAR' | 'PRESENTE' | 'FALTOU' = 'RECUPERAR';

    corrigirFrequencia(a: AgendamentoDTO): void {
        if (this.acoesBloqueadas || (!this.podeCorrigirFrequencia(a) && !this.podeRegularizar(a))) return;
        if (contratoExperimental) {
            if (this.revisaoDesatualizada && !this.agendaRecarregada) return;
            if (!this.revisaoDesatualizada || this.correcao?.id !== a.id) this.justificativa = '';
            this.correcao = a;
            this.revisaoDesatualizada = false;
            this.tipoRegularizacao = 'RECUPERAR';
            this.menuAbertoId = null;
            return;
        }
        this.marcarStatus(a, a.situacaoAtendimento === 'PRESENTE' ? 'FALTOU' : 'PRESENTE');
    }
    get acoesBloqueadas(): boolean {
        return this.atualizandoId !== null || this.carregando || !this.agendaRecarregada;
    }
    confirmarCorrecao(): void {
        const a = this.correcao;
        const justificativa = this.justificativa.trim();
        if (!a || (!this.podeCorrigirFrequencia(a) && !this.podeRegularizar(a)) || this.revisaoDesatualizada || this.acoesBloqueadas || justificativa.length < 10 || justificativa.length > 2000) return;
        this.erroGeral = null;
        const destino = a.frequenciaAssistencial === 'PRESENTE' ? 'FALTOU' : 'PRESENTE';
        const comando = a.situacaoReserva === 'CANCELADA' && a.frequenciaAssistencial === 'NAO_REGISTRADA'
            ? this.tipoRegularizacao === 'RECUPERAR'
                ? this.agendamentoService.recuperarReserva(a.id, a.version, justificativa)
                : this.agendamentoService.regularizarCancelamento(a.id, this.tipoRegularizacao, a.version, justificativa)
            : this.agendamentoService.corrigirFrequencia(a.id, destino, a.version, justificativa);
        this.atualizandoId = a.id;
        comando.subscribe({
            next: () => {
                this.correcao = undefined;
                this.atualizandoId = null;
                this.carregarAgenda(true);
            },
            error: (erro: HttpErrorResponse) => {
                this.atualizandoId = null;
                const resultado = interpretarErroHttp(erro, { escrita: true, padrao: 'Não foi possível atualizar a frequência.' });
                this.erroGeral = resultado.mensagem;
                this.cdr.markForCheck();
                if (resultado.tipo === 'STALE_VERSION' || erro.status === 0 || erro.status >= 500) {
                    this.revisaoDesatualizada = true;
                    this.agendaRecarregada = false;
                }
            },
        });
    }
    remarcar(a: AgendamentoDTO): void {
        if (this.acoesBloqueadas || !this.podeRemarcar(a)) return;
        this.router.navigate(['/agenda/novo'], {
            queryParams: {
                agendamentoOriginalId: a.id
            }
        });
    }

    private marcarStatus(a: AgendamentoDTO, novoStatus: string): void {
        if (this.acoesBloqueadas) return;
        this.atualizandoId = a.id;
        this.erroGeral = null;
        this.menuAbertoId = null;
        this.agendamentoService.atualizarStatus(a.id, novoStatus, a.version).subscribe({
            next: (atualizado) => {
                const i = this.agendamentos.findIndex((x) => x.id === atualizado.id);
                if (i !== -1) this.agendamentos[i] = atualizado;
                this.atualizandoId = null;
                this.cdr.markForCheck();
            },
            error: (erro: HttpErrorResponse) => {
                this.atualizandoId = null;
                const resultado = interpretarErroHttp(erro, { escrita: true, padrao: 'Não foi possível atualizar o status do agendamento.' });
                this.erroGeral = resultado.mensagem;
                if (resultado.tipo === 'STALE_VERSION' || erro.status === 0 || erro.status >= 500) {
                    this.agendaRecarregada = false;
                    if (this.correcao) this.revisaoDesatualizada = true;
                }
                this.cdr.markForCheck();
            }
        });
    }

    classeSituacao(s: string): string {
        return this.statusUi[s]?.classe ?? 'status-neutro';
    }
    classePadraoSituacao(s: string): string {
        return this.statusUi[s]?.patternClass ?? 'pattern-neutro';
    }
    labelSituacao(s: string): string {
        return this.statusUi[s]?.label ?? s;
    }
    descricaoSituacao(s: string): string | null {
        return this.statusUi[s]?.descricao ?? null;
    }
    labelTipoAcompanhamento(v: string | null): string {
        return v?.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (l) => l.toUpperCase()) ?? '-';
    }
    formatarHora(h: string): string {
        return h?.slice(0, 5) ?? '-';
    }
    private minutosDoHorario(h: string): number {
        const [hora, minuto] = this.formatarHora(h).split(':').map(Number);
        return hora * 60 + minuto;
    }
    private ordenar(lista: AgendamentoDTO[]): AgendamentoDTO[] {
        return [...lista].sort((a, b) => a.dataAgendamento.localeCompare(b.dataAgendamento) || a.turnoAgendamento.localeCompare(b.turnoAgendamento) || a.horaAtendimento.localeCompare(b.horaAtendimento) || a.id - b.id);
    }
    private periodoAtual(): {
        inicio: string;fim: string
    } {
        const d = this.criarDataLocal(this.dataSelecionada);
        if (this.visualizacao === 'semana') {
            const i = this.inicioDaSemana(d);
            const f = new Date(i);
            f.setDate(f.getDate() + 6);
            return {
                inicio: this.formatarDataISO(i),
                fim: this.formatarDataISO(f)
            };
        }
        if (this.visualizacao === 'mes') {
            const i = this.inicioDoMes(d);
            const f = new Date(i.getFullYear(), i.getMonth() + 1, 0);
            return {
                inicio: this.formatarDataISO(i),
                fim: this.formatarDataISO(f)
            };
        }
        return {
            inicio: this.dataSelecionada,
            fim: this.dataSelecionada
        };
    }
    private montarGradeMensal(base: Date): DiaCalendario[] {
        const inicioMes = this.inicioDoMes(base);
        const cursor = this.inicioDaSemana(inicioMes);
        const hoje = this.formatarDataISO(new Date());
        return Array.from({
            length: 42
        }, () => {
            const d = new Date(cursor);
            const iso = this.formatarDataISO(d);
            cursor.setDate(cursor.getDate() + 1);
            return {
                data: d,
                iso,
                dia: d.getDate(),
                foraDoMes: d.getMonth() !== inicioMes.getMonth(),
                hoje: iso === hoje,
                selecionado: iso === this.dataSelecionada
            };
        });
    }
    private inicioDaSemana(d: Date): Date {
        const r = new Date(d);
        const day = r.getDay();
        const diff = day === 0 ? -6 : 1 - day;
        r.setDate(r.getDate() + diff);
        return this.semHora(r);
    }
    private inicioDoMes(d: Date): Date {
        return new Date(d.getFullYear(), d.getMonth(), 1);
    }
    private criarDataLocal(iso: string): Date {
        const [a, m, d] = iso.split('-').map(Number);
        return new Date(a, m - 1, d);
    }
    private semHora(d: Date): Date {
        return new Date(d.getFullYear(), d.getMonth(), d.getDate());
    }
    formatarDataISO(data: Date): string {
        return `${data.getFullYear()}-${String(data.getMonth() + 1).padStart(2, '0')}-${String(data.getDate()).padStart(2, '0')}`;
    }
    private formatarDataLonga(d: Date): string {
        return d.toLocaleDateString('pt-BR', {
            weekday: 'long',
            day: 'numeric',
            month: 'long',
            year: 'numeric'
        });
    }
    private formatarDataCurta(d: Date): string {
        return d.toLocaleDateString('pt-BR', {
            day: '2-digit',
            month: '2-digit',
            year: 'numeric'
        });
    }
    formatarMesAno(d: Date): string {
        return d.toLocaleDateString('pt-BR', {
            month: 'long',
            year: 'numeric'
        });
    }
    private iniciarRelogio(): void {
        this.horarioAtual = new Date();
        this.relogioId = setInterval(() => this.horarioAtual = new Date(), 60000);
    }
    private mensagemErroAgenda(): string {
        return this.visualizacao === 'dia' ? 'Não foi possível carregar a agenda do dia.' : this.visualizacao === 'semana' ? 'Não foi possível carregar a agenda da semana.' : 'Não foi possível carregar a agenda do mês.';
    }
}
