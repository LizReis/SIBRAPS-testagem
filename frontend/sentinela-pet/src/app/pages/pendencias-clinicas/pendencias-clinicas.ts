import { CommonModule } from '@angular/common';
import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize, Subscription } from 'rxjs';
import { EstadoRevisaoLocal, PaginaPendenciasLocal, PendenciaLocal, rotuloRevisaoLocal } from '../../services/contrato-clinico-local';
import { interpretarErroHttp } from '../../services/erro-http';
import { RelatorioService } from '../../services/relatorio-service';

@Component({
  selector: 'app-pendencias-clinicas',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './pendencias-clinicas.html',
  styleUrl: '../relatorios/relatorios.css',
})
export class PendenciasClinicas implements OnInit {
  private readonly api = inject(RelatorioService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroyRef = inject(DestroyRef);
  private consulta?: Subscription;
  readonly rotuloRevisao = rotuloRevisaoLocal;
  lista?: PaginaPendenciasLocal;
  selecionada?: PendenciaLocal;
  estado: EstadoRevisaoLocal | '' = '';
  justificativa = '';
  carregando = false;
  salvando = false;
  exigeConsulta = false;
  erro = '';
  aviso = '';
  private paginaAtual = 0;

  ngOnInit(): void { this.carregar(0); }

  carregar(pagina = this.paginaAtual): void {
    if (this.salvando || pagina < 0) return;
    this.consulta?.unsubscribe();
    this.paginaAtual = pagina;
    this.carregando = true;
    this.erro = '';
    this.lista = undefined;
    this.selecionada = undefined;
    this.justificativa = '';
    this.exigeConsulta = true;
    this.consulta = this.api.pendencias(pagina, this.estado || undefined)
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => {
        this.carregando = false;
        this.cdr.markForCheck();
      }))
      .subscribe({
        next: lista => { this.lista = lista; this.exigeConsulta = false; },
        error: e => this.erro = interpretarErroHttp(e, { padrao: 'Não foi possível consultar as pendências da unidade.' }).mensagem,
      });
  }

  detalhar(p: PendenciaLocal): void {
    if (this.salvando || this.carregando || this.exigeConsulta || !this.lista?.itens.includes(p)) return;
    this.selecionada = p;
    this.justificativa = '';
    this.erro = '';
    this.aviso = '';
  }

  get podeDecidir(): boolean {
    const tamanho = this.justificativa.trim().length;
    return !this.carregando && !this.salvando && !this.exigeConsulta &&
      this.selecionada?.estado === 'PENDENTE' && !!this.lista?.itens.includes(this.selecionada) && tamanho >= 10 && tamanho <= 2000;
  }

  decidir(decisao: 'APROVAR' | 'REJEITAR'): void {
    const item = this.selecionada;
    if (!item || !this.podeDecidir) return;
    this.salvando = true;
    this.erro = '';
    this.aviso = '';
    this.api.decidir(item, decisao, this.justificativa.trim())
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => {
        this.salvando = false;
        this.cdr.markForCheck();
      }))
      .subscribe({
        next: () => {
          this.salvando = false;
          this.aviso = 'Decisão registrada. Nenhuma correção clínica foi aplicada por esta ação.';
          this.carregar();
        },
        error: e => {
          this.erro = interpretarErroHttp(e, { escrita: true, padrao: 'Não foi possível registrar a decisão.' }).mensagem;
          this.exigeConsulta = true;
        },
      });
  }

  rotuloAplicacao(p: PendenciaLocal): string {
    const estado = p.revisaoOrigem ? p.aplicacaoOrigem : p.aplicacao;
    switch (estado) {
      case 'RESOLUCAO_REGISTRADA': return 'Resolução registrada';
      case 'ORIGEM_RECONHECIDA': return 'Origem reconhecida';
      case 'REVERTIDA': return 'Revertida';
      case 'INCONCLUSIVA': return 'Inconclusiva';
      case 'SEM_APLICACAO': return 'Sem aplicação';
      default: return 'Aplicação não informada';
    }
  }
}
