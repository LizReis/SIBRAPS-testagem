import { interpretarErroHttp } from '../../services/erro-http';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectorRef, Component, inject, OnInit } from '@angular/core';
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
  private readonly requisicoes = new Map<string, string>();

  ngOnInit() {
    this.usuario.obterUsuarioLogado().subscribe({
      next: u => {
        this.podeEncaminhar = ['ADMINISTRADOR', 'PROFISSIONAL'].includes(u.tipoUsuario);
        this.podeAuditar = u.tipoUsuario === 'ADMINISTRADOR';
      },
      error: () => {
        this.podeEncaminhar = this.podeAuditar = false;
      },
    });
    this.carregar();
  }

  carregar() {
    this.consultando = true;
    this.erro = '';
    this.busca = undefined;
    this.requisicaoLista ??= crypto.randomUUID();
    this.http.get<BuscaLocal>(`${raizClinica}/pacientes/busca-ativa`, {params: {requisicao: this.requisicaoLista}})
      .pipe(finalize(() => this.consultando = false)).subscribe({
        next: busca => { this.busca = busca; this.requisicaoLista = undefined; },
        error: () => this.erro = 'Não foi possível consultar a busca ativa.',
      });
  }

  visualizar(alerta: AlertaLocal) {
    this.registrar(alerta, 'visualizacao', {}, () => {
      this.router.navigate(['/pacientes/detalhes', alerta.idPublico]);
    });
  }

  preparar(alerta: AlertaLocal) {
    this.selecionado = alerta;
    this.destino = '';
    this.descricao = '';
    this.mensagem = '';
  }

  encaminhar() {
    if (!this.selecionado || !this.destino.trim() || this.descricao.trim().length < 10) return;
    this.registrar(this.selecionado, 'encaminhamento',
      {destino: this.destino.trim(), descricao: this.descricao.trim()}, () => {
        this.selecionado = undefined;
        this.mensagem = 'Encaminhamento registrado na auditoria da unidade.';
      });
  }

  carregarAuditoria(pagina: number) {
    if (!this.podeAuditar || pagina < 0) return;
    this.http.get<{content: EventoAuditoria[]; totalPages: number}>(`${raizClinica}/auditoria/busca-ativa`,
      {params: {pagina}}).subscribe({
      next: dados => { this.auditoria = dados; this.pagina = pagina; },
      error: () => this.erro = 'Não foi possível consultar a auditoria da unidade.',
    });
  }

  private registrar(alerta: AlertaLocal, acao: string, dados: object, sucesso: () => void) {
    if (this.registrando) return;
    this.registrando = true;
    this.erro = '';
    const chave = JSON.stringify([alerta.vinculo, alerta.versao, acao, dados]);
    const requisicao = this.requisicoes.get(chave) ?? crypto.randomUUID();
    this.requisicoes.set(chave, requisicao);
    this.http.post(
      `${raizClinica}/pacientes/${alerta.idPublico}/vinculos/${alerta.vinculo}/busca-ativa/${acao}`,
      {...dados, version: alerta.versao, requisicao})
      .pipe(finalize(() => this.registrando = false)).subscribe({
        next: () => { this.requisicoes.delete(chave); sucesso(); },
        error: e => {
          if (e.status >= 400 && e.status < 500) this.requisicoes.delete(chave);
          this.erro = interpretarErroHttp(e, { escrita: true, padrao: 'Não foi possível registrar esta ação.' }).mensagem;
          this.cdr.markForCheck();
        },
      });
  }
}
