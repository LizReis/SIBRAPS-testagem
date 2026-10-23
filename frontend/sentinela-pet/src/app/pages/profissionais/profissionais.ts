import { CommonModule } from '@angular/common';
import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import {
  ProfissionalPayload,
  ProfissionalService,
} from '../../services/profissional/profissional-service';

import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { UnidadeSaudeService } from '../../services/unidade-saude-service';

interface UnidadeFiltro {
  valor: string;
  label: string;
  tipo: 'CAPS' | 'USF';
  cnes?: string;
}

@Component({
  selector: 'app-profissionais',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './profissionais.html',
  styleUrl: './profissionais.css',
})
export class Profissionais implements OnInit {
  termoPesquisa = '';
  tipoUsuarioSelecionado = '';

  unidadeSelecionada = '';
  unidadesDisponiveis: UnidadeFiltro[] = [
    { valor: 'CAPS_AD', label: 'CAPS AD', tipo: 'CAPS' },
    { valor: 'CAPS_I', label: 'CAPS I', tipo: 'CAPS' },
    { valor: 'CAPS_II', label: 'CAPS II', tipo: 'CAPS' },
  ];
  readonly tipoUsuarioOptions = ['ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO'];
  readonly itensPorPagina = 10;
  paginaAtual = 1;

  profissionais: ProfissionalPayload[] = [];

  carregando = false;
  erro: string | null = null;
  podeGerenciar = false;

  constructor(
    private router: Router,
    private profissionalService: ProfissionalService,
    private usuarioLogadoService: UsuarioLogadoService,
    private unidadeSaudeService: UnidadeSaudeService,
    private changeDetector: ChangeDetectorRef,
  ) {}

  ngOnInit(): void {
    this.usuarioLogadoService
      .obterUsuarioLogado()
      .subscribe((usuario) => (this.podeGerenciar = usuario.tipoUsuario === 'ADMINISTRADOR'));
    this.carregarProfissionais();
    this.unidadeSaudeService.listarUnidades().subscribe({
      next: (unidades) => {
        this.unidadesDisponiveis = [
          ...this.unidadesDisponiveis,
          ...unidades.map((usf): UnidadeFiltro => ({
            valor: `USF:${usf.cnes}`, label: usf.nomeUsf, tipo: 'USF', cnes: usf.cnes,
          })),
        ];
        this.changeDetector.markForCheck();
      },
      error: () => {
        this.erro = 'Não foi possível carregar as unidades de saúde.';
        this.changeDetector.markForCheck();
      },
    });
  }

  carregarProfissionais(): void {
    this.carregando = true;
    this.erro = null;

    this.profissionalService.listar().subscribe({
      next: (profissionais) => {
        this.profissionais = profissionais;
        this.carregando = false;
        this.changeDetector.markForCheck();
      },
      error: (erro) => {
        console.error('Erro ao carregar profissionais', erro);
        this.erro = 'Não foi possível carregar os profissionais.';
        this.carregando = false;
        this.changeDetector.markForCheck();
      },
    });
  }

  get totalUsuarios(): number {
    return this.profissionais.length;
  }
  quantidadePorTipo(tipo: string): number {
    return this.profissionais.filter((item) => item.tipoUsuario === tipo).length;
  }
  get profissionaisFiltrados(): ProfissionalPayload[] {
    const termo = this.termoPesquisa.trim().toLowerCase();
    return this.profissionais.filter((item) => {
      const buscaOk =
        !termo ||
        item.nome.toLowerCase().includes(termo) ||
        item.email.toLowerCase().includes(termo);

      const tipoOk =
        !this.tipoUsuarioSelecionado || item.tipoUsuario === this.tipoUsuarioSelecionado;
      const unidadeOk = !this.unidadeSelecionada || (this.unidadeSelecionada.startsWith('USF:')
        ? item.unidadeAtuacao === 'USF' && item.usfAtuacao?.cnes === this.unidadeSelecionada.substring(4)
        : item.unidadeAtuacao === this.unidadeSelecionada);
      return buscaOk && tipoOk && unidadeOk;
    });
  }

  get totalPaginas(): number {
    return Math.ceil(this.profissionaisFiltrados.length / this.itensPorPagina);
  }
  get paginas(): number[] {
    return Array.from({ length: this.totalPaginas }, (_, indice) => indice + 1);
  }
  get profissionaisPaginados(): ProfissionalPayload[] {
    const inicio = (this.paginaAtual - 1) * this.itensPorPagina;
    return this.profissionaisFiltrados.slice(inicio, inicio + this.itensPorPagina);
  }
  get primeiroRegistro(): number {
    return this.profissionaisFiltrados.length
      ? (this.paginaAtual - 1) * this.itensPorPagina + 1
      : 0;
  }
  get ultimoRegistro(): number {
    return Math.min(this.paginaAtual * this.itensPorPagina, this.profissionaisFiltrados.length);
  }
  filtrosAlterados(): void {
    this.paginaAtual = 1;
  }
  irParaPagina(pagina: number): void {
    if (pagina >= 1 && pagina <= this.totalPaginas) this.paginaAtual = pagina;
  }
  iniciais(nome: string): string {
    const partes = nome.trim().split(/\s+/).filter(Boolean);
    return (
      (partes[0]?.[0] ?? '') + (partes.length > 1 ? (partes.at(-1)?.[0] ?? '') : '')
    ).toUpperCase();
  }

  cadastrarNovoProfissional(): void {
    this.router.navigate(['/profissionais/novo']);
  }

  verDetalhes(idPublico: string): void {
    this.router.navigate(['/profissionais/detalhes', idPublico]);
  }

  editarProfissional(idPublico: string): void {
    this.router.navigate(['/profissionais/editar', idPublico]);
  }

  labelEnum(valor: string): string {
    return valor
      .replaceAll('_', ' ')
      .toLowerCase()
      .replace(/\b\w/g, (letra) => letra.toUpperCase());
  }
}
