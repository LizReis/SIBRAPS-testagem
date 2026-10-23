import { CommonModule } from '@angular/common';
import { HttpResponse } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { finalize } from 'rxjs';
import { GrupoTerapeuticoPayload, GrupoTerapeuticoService } from '../../services/grupo-terapeutico-service';
import { ProfissionalService, UsuarioReferencia } from '../../services/profissional/profissional-service';
import { FiltroPeriodo, FiltrosBuscaAtiva, FiltrosFrequenciaGrupos, RelatorioService } from '../../services/relatorio-service';

type TipoRelatorio = 'buscaAtiva' | 'frequenciaGrupo';
type FormatoExportacao = 'PDF' | 'Excel' | 'CSV';

interface PacienteBuscaAtivaMock {
  nome: string;
  documento: string;
  dataUltimoAtendimento: string;
  faltasConsecutivas: number;
  tipoAcompanhamento: 'Individual' | 'Grupo terapêutico';
  telefone: string;
  profissional: string;
}

interface SessaoFrequenciaMock {
  id: number;
  grupo: string;
  data: string;
  profissionalResponsavel: string;
  presentes: string[];
  ausentes: string[];
}

export function converterPeriodo(periodo: string, hoje = new Date()): FiltroPeriodo {
  if (periodo === 'Todo o período') return {};
  const inicio = new Date(hoje.getFullYear(), hoje.getMonth(), hoje.getDate());
  if (periodo === 'Últimos 30 dias') inicio.setDate(inicio.getDate() - 30);
  else if (periodo === 'Últimos 3 meses') inicio.setMonth(inicio.getMonth() - 3);
  else if (periodo === 'Últimos 6 meses') inicio.setMonth(inicio.getMonth() - 6);
  else if (periodo === 'Últimos 12 meses') inicio.setMonth(inicio.getMonth() - 12);
  else return {};
  return { dataInicio: formatarDataLocal(inicio), dataFim: formatarDataLocal(hoje) };
}

function formatarDataLocal(data: Date): string {
  const doisDigitos = (valor: number) => valor.toString().padStart(2, '0');
  return `${data.getFullYear()}-${doisDigitos(data.getMonth() + 1)}-${doisDigitos(data.getDate())}`;
}

@Component({
  selector: 'app-relatorios',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './relatorios.html',
  styleUrl: './relatorios.css',
})
export class Relatorios implements OnInit {
  abaAtiva: TipoRelatorio = 'buscaAtiva';
  formatoSelecionado: FormatoExportacao = 'CSV';
  periodoBusca = 'Últimos 6 meses';
  profissionalSelecionado = '';
  tipoAcompanhamentoSelecionado = 'Individual e grupo';
  grupoSelecionado: number | null = null;
  periodoFrequencia = 'Últimos 6 meses';
  exportandoPdf = false;
  erroExportacao = '';
  profissionais: UsuarioReferencia[] = [];
  grupos: GrupoTerapeuticoPayload[] = [];

  constructor(
    private readonly relatorioService: RelatorioService,
    private readonly profissionalService: ProfissionalService,
    private readonly grupoService: GrupoTerapeuticoService,
  ) {}

  ngOnInit(): void {
    this.profissionalService.listarElegiveisParaReferencia().subscribe({
      next: (profissionais) => this.profissionais = profissionais,
      error: () => this.profissionais = [],
    });
    this.grupoService.listarGrupos().subscribe({
      next: (grupos) => this.grupos = grupos,
      error: () => this.grupos = [],
    });
  }

  readonly periodos = [
    'Últimos 30 dias',
    'Últimos 3 meses',
    'Últimos 6 meses',
    'Últimos 12 meses',
    'Todo o período',
  ];
  readonly tiposAcompanhamento = ['Individual e grupo', 'Individual', 'Grupo terapêutico'];


  // TODO RF21: substituir dados mockados por dados reais da API de relatórios.
  readonly pacientesBuscaAtiva: PacienteBuscaAtivaMock[] = [
    {
      nome: 'Luciana Almeida', documento: '708 4012 3456 7890', dataUltimoAtendimento: '12/06/2026',
      faltasConsecutivas: 4, tipoAcompanhamento: 'Individual', telefone: '(81) 99812-4471',
      profissional: 'Dra. Marina Costa',
    },
    {
      nome: 'Marcos Vinícius Lima', documento: '122.556.890-04', dataUltimoAtendimento: '19/06/2026',
      faltasConsecutivas: 6, tipoAcompanhamento: 'Individual', telefone: '(81) 98115-7720',
      profissional: 'João Ferreira',
    },
  ];

  // TODO RF21: substituir mock de frequência pela API de relatório de grupos.
  readonly sessoesFrequencia: SessaoFrequenciaMock[] = [
    { id: 1, grupo: 'Grupo de Convivência', data: '05/06/2026', profissionalResponsavel: 'João Ferreira', presentes: ['Ana B. Souza', 'Cleide Ferreira', 'Rafael Torres', 'Lúcia Almeida', 'Pedro Ramos', 'Bruna Alves', 'Caio Lima', 'Sara Melo'], ausentes: ['Marcos V. Lima', 'João P. Nogueira'] },
    { id: 2, grupo: 'Grupo de Convivência', data: '19/06/2026', profissionalResponsavel: 'Ana Oliveira', presentes: ['Ana B. Souza', 'Cleide Ferreira', 'Lúcia Almeida', 'Pedro Ramos', 'Bruna Alves', 'Sara Melo'], ausentes: ['Rafael Torres', 'Marcos V. Lima', 'Caio Lima', 'João P. Nogueira'] },
    { id: 3, grupo: 'Grupo de Cuidado em Álcool e Drogas', data: '10/07/2026', profissionalResponsavel: 'Dra. Marina Costa', presentes: ['Rafael Torres', 'João P. Nogueira', 'Caio Lima', 'Pedro Ramos', 'Bruna Alves', 'Sara Melo', 'Lúcia Almeida', 'Ana B. Souza', 'Marcos V. Lima'], ausentes: ['Cleide Ferreira'] },
    { id: 4, grupo: 'Grupo de Cuidado em Álcool e Drogas', data: '24/07/2026', profissionalResponsavel: 'Dra. Marina Costa', presentes: ['João P. Nogueira', 'Pedro Ramos', 'Caio Lima', 'Bruna Alves', 'Sara Melo'], ausentes: ['Rafael Torres', 'Cleide Ferreira', 'Marcos V. Lima', 'Ana B. Souza', 'Lúcia Almeida'] },
    { id: 5, grupo: 'Grupo de Familiares', data: '07/08/2026', profissionalResponsavel: 'Ana Oliveira', presentes: ['Helena Lima', 'Marta Souza', 'Paulo Ramos', 'Célia Torres', 'Alice Melo', 'Mauro Alves', 'Sônia Nunes'], ausentes: ['Ricardo Lima'] },
    { id: 6, grupo: 'Grupo de Familiares', data: '21/08/2026', profissionalResponsavel: 'João Ferreira', presentes: ['Helena Lima', 'Marta Souza', 'Célia Torres', 'Alice Melo', 'Mauro Alves'], ausentes: ['Paulo Ramos', 'Sônia Nunes', 'Ricardo Lima'] },
  ];

  get pacientesFiltrados(): PacienteBuscaAtivaMock[] {
    return this.pacientesBuscaAtiva.filter((paciente) => {
      const profissional = this.profissionais.find((item) => item.idPublico === this.profissionalSelecionado);
      const profissionalCorresponde = !profissional || paciente.profissional === profissional.nome;
      const tipoCorresponde = this.tipoAcompanhamentoSelecionado === 'Individual e grupo'
        || paciente.tipoAcompanhamento === this.tipoAcompanhamentoSelecionado;
      return profissionalCorresponde && tipoCorresponde;
    });
  }

  get sessoesFiltradas(): SessaoFrequenciaMock[] {
    return this.sessoesFrequencia.filter((sessao) =>
      this.grupoSelecionado === null || sessao.grupo === this.grupos.find((grupo) => grupo.id === this.grupoSelecionado)?.tema,
    );
  }

  get mediaPresenca(): number {
    if (this.sessoesFiltradas.length === 0) return 0;
    const total = this.sessoesFiltradas.reduce((soma, sessao) => soma + this.calcularTaxa(sessao), 0);
    return total / this.sessoesFiltradas.length;
  }

  selecionarAba(aba: TipoRelatorio): void {
    this.abaAtiva = aba;
  }

  selecionarFormato(formato: FormatoExportacao): void {
    // TODO RF21: integrar geração real de PDF, Excel e CSV na próxima etapa.
    this.formatoSelecionado = formato;
    if (formato === 'PDF') this.exportarPdf();
    if (formato === 'Excel') this.exportarExcel();
  }

  exportarPdf(): void {
    if (this.exportandoPdf) return;
    this.exportandoPdf = true;
    this.erroExportacao = '';
    const requisicao = this.abaAtiva === 'buscaAtiva'
      ? this.relatorioService.baixarBuscaAtivaPdf(this.filtrosBuscaAtiva())
      : this.relatorioService.baixarFrequenciaGruposPdf(this.filtrosFrequencia());
    const prefixo = this.abaAtiva === 'buscaAtiva' ? 'relatorio-busca-ativa' : 'relatorio-frequencia-grupos';
    requisicao.pipe(finalize(() => this.exportandoPdf = false)).subscribe({
      next: (resposta) => this.baixarResposta(resposta, `${prefixo}-${formatarDataLocal(new Date())}.pdf`, 'application/pdf', 'PDF'),
      error: () => this.erroExportacao = 'Não foi possível gerar o relatório em PDF. Tente novamente.',
    });
  }

  exportarExcel(): void {
    if (this.exportandoPdf) return;
    this.exportandoPdf = true;
    this.erroExportacao = '';
    const requisicao = this.abaAtiva === 'buscaAtiva'
      ? this.relatorioService.baixarBuscaAtivaExcel(this.filtrosBuscaAtiva())
      : this.relatorioService.baixarFrequenciaGruposExcel(this.filtrosFrequencia());
    const prefixo = this.abaAtiva === 'buscaAtiva' ? 'relatorio-busca-ativa' : 'relatorio-frequencia-grupos';
    requisicao.pipe(finalize(() => this.exportandoPdf = false)).subscribe({
      next: (resposta) => this.baixarResposta(resposta, `${prefixo}-${formatarDataLocal(new Date())}.xlsx`,
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'Excel'),
      error: () => this.erroExportacao = 'Não foi possível gerar o relatório em Excel. Tente novamente.',
    });
  }

  filtrosBuscaAtiva(): FiltrosBuscaAtiva {
    const filtros: FiltrosBuscaAtiva = { ...converterPeriodo(this.periodoBusca) };
    if (this.profissionalSelecionado) filtros.profissionalId = this.profissionalSelecionado;
    if (this.tipoAcompanhamentoSelecionado === 'Individual') filtros.tipoAcompanhamento = 'INDIVIDUAL';
    if (this.tipoAcompanhamentoSelecionado === 'Grupo terapêutico') filtros.tipoAcompanhamento = 'GRUPO_TERAPEUTICO';
    return filtros;
  }

  filtrosFrequencia(): FiltrosFrequenciaGrupos {
    const filtros: FiltrosFrequenciaGrupos = { ...converterPeriodo(this.periodoFrequencia) };
    if (this.grupoSelecionado !== null) filtros.grupoId = this.grupoSelecionado;
    return filtros;
  }

  private baixarResposta(resposta: HttpResponse<Blob>, fallback: string, tipoEsperado: string, formato: string): void {
    const blob = resposta.body;
    const contentType = resposta.headers.get('Content-Type') || blob?.type;
    if (!blob || !contentType?.toLowerCase().includes(tipoEsperado)) {
      this.erroExportacao = `Não foi possível gerar o relatório em ${formato}. Tente novamente.`;
      return;
    }
    const nome = this.extrairNomeArquivo(resposta.headers.get('Content-Disposition')) || fallback;
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = nome;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  private extrairNomeArquivo(contentDisposition: string | null): string | null {
    if (!contentDisposition) return null;
    const utf8 = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i);
    const simples = contentDisposition.match(/filename\s*=\s*"?([^";]+)"?/i);
    const valor = utf8?.[1] ?? simples?.[1];
    if (!valor) return null;
    try { return decodeURIComponent(valor.trim()); } catch { return valor.trim(); }
  }

  calcularTaxa(sessao: SessaoFrequenciaMock): number {
    const total = sessao.presentes.length + sessao.ausentes.length;
    return total === 0 ? 0 : (sessao.presentes.length / total) * 100;
  }

  formatarPercentual(valor: number): string {
    return valor.toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  }

  resumirNomes(nomes: string[]): string {
    if (nomes.length <= 3) return nomes.join(', ');
    return `${nomes.slice(0, 3).join(', ')}, +${nomes.length - 3}`;
  }
}
