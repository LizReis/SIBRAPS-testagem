import { interpretarErroHttp } from '../../services/erro-http';
import { contratoExperimental } from '../../services/configuracao-contrato';
import {
    CommonModule
} from '@angular/common';
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
    RouterLink
} from '@angular/router';
import {
    EMPTY,
    Observable,
    Subject,
    catchError,
    of,
    timer,
    switchMap,
    takeUntil
} from 'rxjs';
import {
    PacienteLista,
    PacientePesquisa,
    PacienteService
} from '../../services/paciente/paciente-service';
import {
    UsuarioLogadoService
} from '../../services/usuario-logado-service';

import { podeCadastrarPaciente, podeEditarPaciente } from '../../auth/permissoes';

@Component({
    selector: 'app-pacientes',
    standalone: true,
    imports: [CommonModule, FormsModule, RouterLink],
    templateUrl: './pacientes.html',
    styleUrl: './pacientes.css'
})
export class Pacientes implements OnInit, OnDestroy {
    readonly Math = Math;
    termo = '';
    classificacao = '';
    status = contratoExperimental ? '' : 'ATIVO';
    acompanhamento = '';
    pagina = 0;
    tamanho = 10;
    dados ? : PacientePesquisa;
    carregando = false;
    erroConsulta = '';
    podeCadastrar = false;
    podeEditar = false;
    menu ? : string;
    private busca$ = new Subject<boolean>();
    private destroy$ = new Subject < void > ();
    constructor(private service: PacienteService, usuario: UsuarioLogadoService, private readonly cdr: ChangeDetectorRef) {
        usuario.obterUsuarioLogado().subscribe({
            next: u => {
                this.podeCadastrar = podeCadastrarPaciente(u.tipoUsuario);
                this.podeEditar = podeEditarPaciente(u.tipoUsuario);
            },
            error: e => this.falharConsulta(e),
        });
    }
    ngOnInit() {
        this.busca$.pipe(
            switchMap(digitada => {
                if (digitada && !this.buscaValida(this.termo)) {
                    this.carregando = false;
                    this.cdr.markForCheck();
                    return EMPTY;
                }
                const espera: Observable<number | null> = digitada ? timer(350) : of(null);
                return espera.pipe(
                    switchMap(() => this.consultar()),
                    catchError(e => {
                        this.falharConsulta(e);
                        return EMPTY;
                    }),
                );
            }),
            takeUntil(this.destroy$),
        ).subscribe(dados => {
            this.dados = dados;
            this.carregando = false;
            this.cdr.markForCheck();
        });
        this.carregar();
    }
    ngOnDestroy() {
        this.destroy$.next();
        this.destroy$.complete();
    }
    buscaValida(v: string) {
        const d = v.replace(/\D/g, '');
        return !v.trim() || (!!d && [11, 15].includes(d.length)) || (!d && v.trim().length >= 3);
    }
    pesquisar() {
        this.pagina = 0;
        this.busca$.next(true);
    }
    filtrar() {
        this.pagina = 0;
        this.carregar();
    }
    consultar() {
        this.erroConsulta = '';
        this.carregando = true;
        return this.service.pesquisar({
            q: this.termo.trim() || undefined,
            classificacao: this.classificacao || undefined,
            status: this.status || undefined,
            tipoAcompanhamento: this.acompanhamento || undefined,
            page: this.pagina,
            size: this.tamanho
        });
    }
    carregar() {
        this.busca$.next(false);
    }
    private falharConsulta(erro: unknown): void {
        this.carregando = false;
        this.erroConsulta = interpretarErroHttp(erro, { padrao: 'Não foi possível consultar a lista de pacientes.' }).mensagem;
        this.cdr.markForCheck();
    }
    limpar() {
        this.termo = '';
        this.classificacao = '';
        this.status = contratoExperimental ? '' : 'ATIVO';
        this.acompanhamento = '';
        this.pagina = 0;
        this.carregar();
    }
    ir(p: number) {
        if (p >= 0 && p < (this.dados?.totalPaginas || 0)) {
            this.pagina = p;
            this.carregar();
        }
    }
    iniciais(n: string) {
        return n.split(/\s+/).slice(0, 2).map(x => x[0]).join('').toUpperCase()
    }
    documento(p: PacienteLista) {
        const d = (p.cpf || '').replace(/\D/g, '');
        return d.length === 11 ? `CPF •••.${d.slice(3,6)}.${d.slice(6,9)}-••` : `CNS •••••••••${(p.cns||'').slice(-4)}`
    }
    label(v?: string | null) {
        return (v || 'Não confirmado').replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, x => x.toUpperCase())
    }
    @HostListener('document:click') fechar() {
        this.menu = undefined
    }
    @HostListener('document:keydown.escape') escape() {
        this.menu = undefined
    }
}