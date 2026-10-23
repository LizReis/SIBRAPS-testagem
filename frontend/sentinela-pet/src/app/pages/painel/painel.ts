import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { EvolucaoPainel, FiltrosPainel, PainelPayload, PainelService } from '../../services/painel-service';

type SegmentoDonut = {
  titulo: string;
  classe: 'green' | 'yellow' | 'red';
  quantidade: number;
  percentual: number;
  tamanho: number;
  inicio: number;
  meio: number;
};

@Component({
  selector: 'app-painel',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './painel.html',
  styleUrls: ['./painel.css', './painel-ajustes.css'],
})
export class Painel implements OnInit {
  private readonly painelService = inject(PainelService);
  dados?: PainelPayload;
  carregando = true;
  erro = false;
  maisFiltrosAberto = false;
  filtros: FiltrosPainel = { periodoMeses: 6 };
  rascunhoTipo = '';
  rascunhoRua = '';

  readonly riscos = [
    { chave: 'vermelhos', percentual: 'percentualVermelhos', titulo: 'Busca ativa', classe: 'red', icone: 'warning' },
    { chave: 'amarelos', percentual: 'percentualAmarelos', titulo: 'Atenção', classe: 'yellow', icone: 'warning' },
    { chave: 'verdes', percentual: 'percentualVerdes', titulo: 'Regular', classe: 'green', icone: 'check_circle' },
  ] as const;

  ngOnInit(): void { this.carregar(); }
  carregar(): void {
    this.carregando = true; this.erro = false;
    this.painelService.buscarResumo(this.filtros).pipe(finalize(() => this.carregando = false)).subscribe({
      next: dados => this.dados = dados,
      error: () => this.erro = true,
    });
  }
  alterarFiltroPrincipal(): void { this.carregar(); }
  aplicarMaisFiltros(): void {
    this.filtros = { ...this.filtros,
      tipoAcompanhamento: this.rascunhoTipo || undefined,
      situacaoRua: this.rascunhoRua === '' ? undefined : this.rascunhoRua === 'true' };
    this.maisFiltrosAberto = false; this.carregar();
  }
  limparMaisFiltros(): void { this.rascunhoTipo = ''; this.rascunhoRua = ''; }
  valor(chave: 'verdes'|'amarelos'|'vermelhos'): number { return this.dados?.distribuicaoClassificacao[chave] ?? 0; }
  percentual(chave: 'percentualVerdes'|'percentualAmarelos'|'percentualVermelhos'): number { return this.dados?.distribuicaoClassificacao[chave] ?? 0; }
  formatarPercentual(valor: number): string { return new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(valor); }
  iniciais(nome: string): string { return nome.split(/\s+/).filter(Boolean).slice(0, 2).map(p => p[0]).join('').toUpperCase(); }
  rotuloRisco(risco: string): string { return risco === 'VERMELHO' ? 'Busca ativa' : risco === 'AMARELO' ? 'Atenção' : 'Regular'; }

  get segmentosDonut(): SegmentoDonut[] {
    const distribuicao = this.dados?.distribuicaoClassificacao;
    const segmentos = [
      { titulo: 'Regular', classe: 'green' as const, quantidade: distribuicao?.verdes ?? 0, percentual: distribuicao?.percentualVerdes ?? 0 },
      { titulo: 'Atenção', classe: 'yellow' as const, quantidade: distribuicao?.amarelos ?? 0, percentual: distribuicao?.percentualAmarelos ?? 0 },
      { titulo: 'Busca ativa', classe: 'red' as const, quantidade: distribuicao?.vermelhos ?? 0, percentual: distribuicao?.percentualVermelhos ?? 0 },
    ];
    const somaPercentuais = segmentos.reduce((soma, segmento) => soma + Math.max(0, segmento.percentual), 0);
    let inicio = 0;
    return segmentos.map(segmento => {
      const tamanho = somaPercentuais > 0 ? Math.max(0, segmento.percentual) / somaPercentuais * 100 : 0;
      const resultado = { ...segmento, tamanho, inicio, meio: inicio + tamanho / 2 };
      inicio += tamanho;
      return resultado;
    });
  }

  get possuiMaisDeUmSegmentoDonut(): boolean {
    return this.segmentosDonut.filter(segmento => segmento.tamanho > 0).length > 1;
  }

  get descricaoDonut(): string {
    return `Distribuição dos pacientes: ${this.segmentosDonut.map(segmento => `${segmento.titulo} ${segmento.quantidade}`).join(', ')}.`;
  }

  coordenadaPolar(percentual: number, raio: number, eixo: 'x' | 'y'): number {
    const angulo = (percentual * 3.6 - 90) * Math.PI / 180;
    return 100 + raio * (eixo === 'x' ? Math.cos(angulo) : Math.sin(angulo));
  }

  get maxEvolucao(): number {
    const valores = (this.dados?.evolucao ?? []).flatMap(p => [p.verdes, p.amarelos, p.vermelhos]);
    return Math.max(1, ...valores);
  }

  get maxEvolucaoEscala(): number {
    return this.tetoEscala(this.maxEvolucao);
  }

  get ticksEvolucao(): { valor: number; y: number }[] {
    const topo = this.maxEvolucaoEscala;
    return Array.from({ length: 5 }, (_, i) => ({
      valor: Math.round(topo - (topo * i) / 4),
      y: 14 + i * 17.5,
    }));
  }

  pontos(campo: keyof Pick<EvolucaoPainel, 'verdes'|'amarelos'|'vermelhos'>): string {
    const serie = this.dados?.evolucao ?? [];
    if (!serie.length) return '';
    return serie.map((p, i) => `${this.xPonto(i, serie.length)},${this.yPonto(p[campo])}`).join(' ');
  }

  xPonto(indice: number, total: number): number { return total <= 1 ? 50 : 10 + indice * 82 / (total - 1); }
  yPonto(valor: number): number { return 84 - valor * 70 / this.maxEvolucaoEscala; }

  tooltip(p: EvolucaoPainel): string { return `${p.rotulo}: Regular ${p.verdes}, Atenção ${p.amarelos}, Busca ativa ${p.vermelhos}`; }

  private tetoEscala(valor: number): number {
    if (valor <= 0) return 5;
    const magnitude = Math.pow(10, Math.floor(Math.log10(valor)));
    const normalizado = valor / magnitude;
    const fator = normalizado <= 1 ? 1 : normalizado <= 2 ? 2 : normalizado <= 2.5 ? 2.5 : normalizado <= 5 ? 5 : 10;
    return Math.max(5, fator * magnitude);
  }
}
