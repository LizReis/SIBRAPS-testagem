import { interpretarErroHttp } from '../../services/erro-http';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectorRef, Component, DestroyRef, inject, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { raizClinica } from '../../services/configuracao-contrato';
import { AlertaLocal, BuscaLocal, rotuloFaltasLocal, rotuloRiscoLocal } from '../../services/contrato-clinico-local';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';

interface EventoAuditoria {
  id: string;
  tipoEvento: string;
  ocorridoEm: string;
  perfil: string;
  destino: string | null;
  descricao?: string | null;
  pacienteId?: string | null;
  usuarioId?: string;
  situacao?: string;
  quantidade?: number;
}

@Component({
  selector: 'app-busca-ativa-local',
  imports: [FormsModule, DatePipe],
  templateUrl: './busca-ativa-local.html',
  styles: [`
    :host { display: block; margin-block: 1.5rem; }
    section { padding: 1rem; border: 1px solid #d7dce3; border-radius: .75rem; }
    li { margin-block: .75rem; }
    label { display: block; margin-block: .5rem; }
    input, textarea { display: block; max-width: 100%; }
    button { margin: .25rem .5rem .25rem 0; }
  `],
})
export class BuscaAtivaLocal implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroy = inject(DestroyRef);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly usuario = inject(UsuarioLogadoService);
  readonly risco = rotuloRiscoLocal;
  readonly faltas = rotuloFaltasLocal;
  busca?: BuscaLocal;
  erro = '';
  mensagem = '';
  consultando = false;
  registrando = false;
  podeEncaminhar = false;
  podeAuditar = false;
  selecionado?: AlertaLocal;
  destino = '';
  descricao = '';
  auditoria?: {content: EventoAuditoria[]; totalPages: number};
  pagina = 0;
  private requisicaoLista?: string;
  exigeConsulta = false;
  consultandoAuditoria = false;
  erroAuditoria = '';
  pendente?: {alerta:AlertaLocal; acao:'visualizacao'|'encaminhamento'; dados:object; requisicao:string; sucesso:()=>void};
  get bloqueado(): boolean { return this.registrando || this.consultando || this.exigeConsulta || !!this.pendente; }
  get encaminhamentoValido(): boolean {
    return this.podeEncaminhar && !!this.selecionado && this.selecionado.acao !== 'REVISAO_HISTORICA' &&
      this.destino.trim().length > 0 && this.destino.trim().length <= 200 &&
      this.descricao.trim().length >= 10 && this.descricao.trim().length <= 1000 && !this.bloqueado;
  }

  ngOnInit() {
    this.usuario.obterUsuarioLogado().pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: u => {
        this.podeEncaminhar = ['ADMINISTRADOR', 'PROFISSIONAL'].includes(u.tipoUsuario);
        this.podeAuditar = u.tipoUsuario === 'ADMINISTRADOR';
        this.cdr.markForCheck();
      },
      error: () => {
        this.podeEncaminhar = this.podeAuditar = false;
        this.auditoria = undefined;
        this.cdr.markForCheck();
      },
    });
    this.carregar();
  }

  carregar() {
    if (this.registrando || this.consultando || this.pendente) return;
    this.consultando = true;
    this.erro = '';
    this.busca = undefined;
    this.selecionado = undefined;
    this.destino = this.descricao = '';
    this.exigeConsulta = true;
    this.requisicaoLista ??= crypto.randomUUID();
    this.http.get<BuscaLocal>(`${raizClinica}/pacientes/busca-ativa`, {params: {requisicao: this.requisicaoLista}})
      .pipe(takeUntilDestroyed(this.destroy), finalize(() => { this.consultando = false; this.cdr.markForCheck(); })).subscribe({
        next: busca => { this.busca = busca; this.requisicaoLista = undefined; this.exigeConsulta = false; },
        error: e => this.erro = interpretarErroHttp(e,{padrao:'Não foi possível consultar a busca ativa.'}).mensagem,
      });
  }

  visualizar(alerta: AlertaLocal) {
    this.registrar(alerta, 'visualizacao', {}, () => {
      this.router.navigate(['/pacientes/detalhes', alerta.idPublico]);
    });
  }

  preparar(alerta: AlertaLocal) {
    if (this.bloqueado || !this.podeEncaminhar || alerta.acao === 'REVISAO_HISTORICA' || !this.busca?.alertas.includes(alerta)) return;
    this.selecionado = alerta;
    this.destino = '';
    this.descricao = '';
    this.mensagem = '';
  }

  encaminhar() {
    if (!this.selecionado || !this.encaminhamentoValido) return;
    this.registrar(this.selecionado, 'encaminhamento',
      {destino: this.destino.trim(), descricao: this.descricao.trim()}, () => {
        this.selecionado = undefined;
        this.mensagem = 'Encaminhamento registrado na auditoria da unidade.';
      });
  }

  carregarAuditoria(pagina: number) {
    if (!this.podeAuditar || pagina < 0 || this.consultandoAuditoria) return;
    this.consultandoAuditoria = true;
    this.auditoria = undefined;
    this.erroAuditoria = '';
    this.http.get<{content: EventoAuditoria[]; totalPages: number}>(`${raizClinica}/auditoria/busca-ativa`,
      {params: {pagina}}).pipe(takeUntilDestroyed(this.destroy),finalize(()=>{
        this.consultandoAuditoria=false;this.cdr.markForCheck();
      })).subscribe({
      next: dados => { if(this.podeAuditar){this.auditoria = dados; this.pagina = pagina;} },
      error: e => this.erroAuditoria = interpretarErroHttp(e,{padrao:'Não foi possível consultar a auditoria da unidade.'}).mensagem,
    });
  }

  private registrar(alerta: AlertaLocal, acao: 'visualizacao'|'encaminhamento', dados: object, sucesso: () => void) {
    if (this.bloqueado || !this.busca?.alertas.includes(alerta) ||
        (acao==='encaminhamento' && (!this.podeEncaminhar || alerta.acao==='REVISAO_HISTORICA'))) return;
    this.pendente={alerta,acao,dados,requisicao:crypto.randomUUID(),sucesso};
    this.enviarPendente();
  }

  /** Recuperação explicitamente solicitada da MESMA operação, com mesma chave RNF17. */
  confirmarResultado():void {
    if(this.pendente && !this.registrando &&
      (this.pendente.acao==='visualizacao' || this.podeEncaminhar)) this.enviarPendente();
  }

  private enviarPendente():void {
    const p=this.pendente;
    if(!p||this.registrando)return;
    this.registrando=true;this.erro='';
    this.http.post(
      `${raizClinica}/pacientes/${p.alerta.idPublico}/vinculos/${p.alerta.vinculo}/busca-ativa/${p.acao}`,
      {...p.dados,version:p.alerta.versao,requisicao:p.requisicao})
      .pipe(takeUntilDestroyed(this.destroy),finalize(()=>{this.registrando=false;this.cdr.markForCheck();})).subscribe({
        next:()=>{
          this.pendente=undefined;this.exigeConsulta=true;this.auditoria=undefined;
          p.sucesso();
        },
        error:e=>{
          this.exigeConsulta=true;
          const status=e?.status;
          if(typeof status==='number'&&status>=400&&status<500&&status!==408&&status!==429)this.pendente=undefined;
          this.erro=interpretarErroHttp(e,{escrita:true,padra:'Não foi possível registrar esta ação.'}).mensagem;
        },
      });
  }
}
