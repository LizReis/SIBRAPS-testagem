import {
    CommonModule
} from '@angular/common';
import {
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
    Subject,
    debounceTime,
    distinctUntilChanged,
    filter,
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
    status = 'ATIVO';
    acompanhamento = '';
    pagina = 0;
    tamanho = 10;
    dados ? : PacientePesquisa;
    carregando = false;
    podeCadastrar = false;
    podeEditar = false;
    menu ? : string;
    private busca$ = new Subject < string > ();
    private destroy$ = new Subject < void > ();
    constructor(private service: PacienteService, usuario: UsuarioLogadoService) {
        usuario.obterUsuarioLogado().subscribe(u => {
            this.podeCadastrar = podeCadastrarPaciente(u.tipoUsuario);
            this.podeEditar = podeEditarPaciente(u.tipoUsuario);
        });
    }
    ngOnInit() {
        this.busca$.pipe(debounceTime(350), distinctUntilChanged(), filter(v => this.buscaValida(v)), switchMap(() => {
            this.pagina = 0;
            return this.consultar()
        }), takeUntil(this.destroy$)).subscribe();
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
        this.busca$.next(this.termo);
    }
    filtrar() {
        this.pagina = 0;
        this.carregar();
    }
    consultar() {
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
        this.consultar().subscribe({
            next: d => {
                this.dados = d;
                this.carregando = false
            },
            error: () => {
                this.carregando = false
            }
        });
    }
    limpar() {
        this.termo = '';
        this.classificacao = '';
        this.status = 'ATIVO';
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
    label(v ? : string) {
        return (v || '').replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, x => x.toUpperCase())
    }
    @HostListener('document:click') fechar() {
        this.menu = undefined
    }
    @HostListener('document:keydown.escape') escape() {
        this.menu = undefined
    }
}