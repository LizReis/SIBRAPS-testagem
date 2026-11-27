import { interpretarErroHttp } from '../../services/erro-http';
import {
    CommonModule
} from '@angular/common';
import {
    ChangeDetectorRef,
    DestroyRef,
    Component,
    HostListener,
    OnInit
} from '@angular/core';
import {
    FormsModule
} from '@angular/forms';
import {
    ActivatedRoute,
    Router,
    RouterLink
} from '@angular/router';
import {
    PacienteDetalhe,
    PacienteService
} from '../../services/paciente/paciente-service';
import {
    UsuarioLogadoService
} from '../../services/usuario-logado-service';
import { contratoExperimental } from '../../services/configuracao-contrato';
import { ProfissionalService, UsuarioReferencia } from '../../services/profissional/profissional-service';
import { inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { podeCriarAgendamento, podeEditarPaciente } from '../../auth/permissoes';

@Component({
    selector: 'app-detalhe-paciente',
    standalone: true,
    imports: [CommonModule, FormsModule, RouterLink],
    templateUrl: './detalhe-paciente.html',
    styleUrl: './detalhe-paciente.css'
})
export class DetalhePaciente implements OnInit {
    detalhe?: PacienteDetalhe;
    id = '';
    carregando = true;
    erro = '';
    modal: '' | 'encerrar' | 'busca' = '';
    acaoAdministrativa: '' | 'configurar' | 'reativar' | 'inativar' = '';
    readonly contratoExperimental = contratoExperimental;
    podeAdministrar = false;
    modalidade = '';
    referencia: string | null | undefined;
    referenciasDisponiveis = false;
    referencias: UsuarioReferencia[] = [];
    motivoReativacao = '';
    private readonly cdr = inject(ChangeDetectorRef);
    private readonly destroy = inject(DestroyRef);
    private consulta?: Subscription;
    private readonly profissionais = contratoExperimental ? inject(ProfissionalService) : null;
    motivo = '';
    descricao = '';
    salvando = false;
    sucesso = '';
    erroModal = '';
    podeGerenciar = false;
    podeEditar = false;
    podeAgendar = false;
    vinculoDesatualizado = false;
    constructor(private route: ActivatedRoute, private router: Router, private service: PacienteService, usuario: UsuarioLogadoService) {
        usuario.obterUsuarioLogado().pipe(takeUntilDestroyed(this.destroy)).subscribe({
            next: u => {
                this.podeGerenciar = ['ADMINISTRADOR', 'PROFISSIONAL'].includes(u.tipoUsuario);
                this.podeEditar = podeEditarPaciente(u.tipoUsuario);
                this.podeAgendar = podeCriarAgendamento(u.tipoUsuario);
                this.podeAdministrar = contratoExperimental && u.tipoUsuario === 'ADMINISTRADOR';
                this.cdr.markForCheck();
            },
            error: () => {
                this.podeGerenciar = this.podeEditar = this.podeAdministrar = this.podeAgendar = false;
                this.cdr.markForCheck();
            },
        });
    }
    ngOnInit() {
        this.id = this.route.snapshot.paramMap.get('id') || '';
        if (!this.id) {
            this.erro = 'Paciente não informado';
            this.carregando = false;
            return
        }
        this.carregar()
    }
    carregar() {
        if (this.salvando) return;
        this.consulta?.unsubscribe();
        this.detalhe = undefined;
        this.carregando = true;
        this.erro = '';
        this.consulta = this.service.buscarDetalhe(this.id).pipe(takeUntilDestroyed(this.destroy)).subscribe({
            next: d => {
                const vinculo = this.route.snapshot.queryParamMap?.get('vinculo');
                if (vinculo && d.paciente.local?.vinculo !== vinculo) {
                    this.detalhe = undefined;
                    this.erro = 'O recurso solicitado não foi encontrado.';
                    this.carregando = false;
                    this.cdr.markForCheck();
                    return;
                }
                this.erro = '';
                this.detalhe = d;
                this.carregando = false;
                this.vinculoDesatualizado = false;
                this.cdr.markForCheck();
            },
            error: e => {
                this.detalhe = undefined;
                this.erro = interpretarErroHttp(e, { padrao: 'Não foi possível carregar os dados deste paciente.' }).mensagem;
                this.carregando = false;
                this.cdr.markForCheck();
            }
        })
    }
    get p() {
        return this.detalhe?.paciente
    }
    get ativo() {
        return this.p?.statusPaciente === 'ATIVO'
    }
    iniciais() {
        return (this.p?.nome || '').split(/\s+/).slice(0, 2).map(x => x[0]).join('').toUpperCase()
    }
    idade() {
        if (!this.p) return 0;
        const n = new Date(this.p.dataNascimento + 'T00:00:00'),
            h = new Date();
        let i = h.getFullYear() - n.getFullYear();
        if (h < new Date(h.getFullYear(), n.getMonth(), n.getDate())) i--;
        return i
    }
    label(v?: string | null) {
        return (v || 'Não informado').replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, x => x.toUpperCase())
    }
    data(v?: string) {
        return v ? new Intl.DateTimeFormat('pt-BR').format(new Date(v + 'T00:00:00')) : 'Sem presença registrada'
    }
    mask(v: string | undefined, t: 'cpf' | 'cns') {
        const d = (v || '').replace(/\D/g, '');
        return t === 'cpf' ? `•••.${d.slice(3, 6)}.${d.slice(6, 9)}-••` : `•••••••••${d.slice(-6)}`
    }
    encerrar() {
        if (this.salvando || this.carregando || this.vinculoDesatualizado || !this.podeGerenciar || !this.ativo
            || !this.motivo || this.motivo === 'OUTRO' && !this.descricao.trim()) return;
        this.erroModal = '';
        this.salvando = true;
        this.service.encerrar(this.id, this.motivo, this.descricao, this.p?.local).subscribe({
            next: () => {
                this.modal = '';
                this.salvando = false;
                this.carregar()
            },
            error: e => {
                const resultado = interpretarErroHttp(e, { escrita: true, padrao: 'Não foi possível encerrar.' });
                this.erroModal = resultado.mensagem;
                this.vinculoDesatualizado = resultado.tipo === 'STALE_VERSION';
                this.cdr.markForCheck();
                this.salvando = false
            }
        })
    }

    abrirAcaoAdministrativa(acao: 'configurar' | 'reativar' | 'inativar'): void {
        if (this.salvando || this.carregando || this.vinculoDesatualizado || !this.podeAdministrar || !this.p?.local) return;
        this.acaoAdministrativa = acao;
        this.erroModal = '';
        this.modalidade = this.p?.tipoAcompanhamento ?? '';
        this.referencia = this.detalhe?.referenciaInconclusiva
            ? undefined : this.p?.profissionalReferencia?.idPublico ?? null;
        this.referenciasDisponiveis = false;
        this.referencias = [];
        this.motivoReativacao = '';
        if (acao === 'configurar') this.profissionais?.listarElegiveisParaReferencia().subscribe({
            next: referencias => { this.referencias = referencias; this.referenciasDisponiveis = true; },
            error: () => this.erroModal = 'Não foi possível consultar profissionais de referência.',
        });
    }

    salvarAcaoAdministrativa(): void {
        const acao = this.acaoAdministrativa;
        const local = this.p?.local;
        if (!acao || !local || this.salvando || this.carregando || this.vinculoDesatualizado || !this.podeAdministrar
            || (acao === 'reativar' ? !this.p?.statusPaciente || this.ativo : !this.ativo)) return;
        if ((acao === 'configurar' && (!this.modalidade || !this.referenciasDisponiveis || this.referencia === undefined)) || (acao === 'reativar' && !this.motivoReativacao.trim())) return;
        const comando = acao === 'configurar'
            ? this.service.configurarAcompanhamento(this.id, this.modalidade, this.referencia ?? null, local)
            : acao === 'reativar'
                ? this.service.reativarAcompanhamento(this.id, this.motivoReativacao.trim(), local)
                : this.service.inativarPaciente(this.id, local);
        this.salvando = true;
        this.erroModal = '';
        comando.subscribe({
            next: () => {
                this.salvando = false;
                this.acaoAdministrativa = '';
                this.sucesso = 'Acompanhamento atualizado.';
                this.carregar();
            },
            error: erro => {
                this.salvando = false;
                const resultado = interpretarErroHttp(erro, { escrita: true, padrao: 'Não foi possível atualizar o vínculo.' });
                this.erroModal = resultado.mensagem;
                this.vinculoDesatualizado = resultado.tipo === 'STALE_VERSION';
                this.cdr.markForCheck();
            },
        });
    }

    abrirBuscaAtiva(): void {
        if (!this.podeRegistrarBusca) return;
        this.descricao = '';
        this.erroModal = '';
        this.sucesso = '';
        this.modal = 'busca';
    }
    get podeRegistrarBusca(): boolean {
        return !this.salvando && !this.carregando && !this.vinculoDesatualizado && this.podeGerenciar && this.ativo
            && this.detalhe?.classificacaoRisco === 'VERMELHO';
    }
    registrar() {
        if (this.modal !== 'busca' || !this.podeRegistrarBusca) return;
        const descricao = this.descricao.trim();

        this.erroModal = '';

        if (!descricao) {
            this.erroModal = 'Informe uma descrição para registrar a busca ativa.';
            return;
        }

        this.salvando = true;

        this.service.registrarBuscaAtiva(this.id, descricao, this.p?.local).subscribe({
            next: () => {
                this.modal = '';
                this.descricao = '';
                this.erroModal = '';
                this.salvando = false;

                this.sucesso = 'Busca ativa registrada com sucesso.';
                this.carregar();
            },

            error: (e) => {
                const resultado = interpretarErroHttp(e, { escrita: true, padrao: 'Não foi possível registrar a busca ativa.' });
                this.erroModal = resultado.mensagem;
                this.vinculoDesatualizado = ['STALE_VERSION', 'NETWORK_ERROR', 'SERVICE_UNAVAILABLE', 'DATABASE_FAILURE', 'INTERNAL_ERROR', 'UNKNOWN_ERROR'].includes(resultado.tipo);
                this.cdr.markForCheck();

                this.salvando = false;
            }
        });
    }
    abrirHistoricoPaciente() {
        this.router.navigate(['/pacientes/detalhes', this.id, 'historico'])
    }
    @HostListener('document:keydown.escape') escape() {
        if (this.salvando) return;
        this.modal = '';
        this.acaoAdministrativa = '';
    }


}
