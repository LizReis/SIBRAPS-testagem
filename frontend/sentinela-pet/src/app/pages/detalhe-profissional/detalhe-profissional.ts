import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import {
  ProfissionalPayload,
  ProfissionalService,
} from '../../services/profissional/profissional-service';

import { UsuarioLogadoService } from '../../services/usuario-logado-service';

@Component({
  selector: 'app-detalhe-profissional',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './detalhe-profissional.html',
  styleUrl: './detalhe-profissional.css',
})
export class DetalheProfissional implements OnInit {
  profissional: ProfissionalPayload | null = null;

  carregando = false;
  erro: string | null = null;

  excluindo = false;
  confirmandoExclusao = false;
  podeGerenciar = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private profissionalService: ProfissionalService,
    private usuarioLogadoService: UsuarioLogadoService,
  ) {}

  ngOnInit(): void {
    this.usuarioLogadoService
      .obterUsuarioLogado()
      .subscribe((usuario) => (this.podeGerenciar = usuario.tipoUsuario === 'ADMINISTRADOR'));

    const idPublico = this.route.snapshot.paramMap.get('id');

    if (!idPublico) {
      this.erro = 'Profissional não informado.';
      return;
    }

    this.carregarProfissional(idPublico);
  }

  carregarProfissional(idPublico: string): void {
    this.carregando = true;
    this.erro = null;

    this.profissionalService.buscarPorId(idPublico).subscribe({
      next: (profissional) => {
        this.profissional = profissional;
        this.carregando = false;
      },
      error: (erro) => {
        console.error('Erro ao carregar profissional', erro);
        this.erro = 'Não foi possível carregar os dados do profissional.';
        this.carregando = false;
      },
    });
  }

  voltar(): void {
    this.router.navigate(['/profissionais']);
  }

  editarProfissional(): void {
    if (!this.profissional?.idPublico) return;
    this.router.navigate(['/profissionais/editar', this.profissional.idPublico]);
  }

  pedirConfirmacaoExclusao(): void {
    this.confirmandoExclusao = true;
  }

  cancelarExclusao(): void {
    this.confirmandoExclusao = false;
  }

  confirmarExclusao(): void {
    if (!this.profissional?.idPublico) return;

    this.excluindo = true;

    this.profissionalService.remover(this.profissional.idPublico).subscribe({
      next: () => {
        this.excluindo = false;
        this.router.navigate(['/profissionais']);
      },
      error: (erro) => {
        console.error('Erro ao excluir profissional', erro);
        this.excluindo = false;
        this.confirmandoExclusao = false;
        this.erro = 'Não foi possível excluir o profissional. Tente novamente.';
      },
    });
  }

  labelEnum(valor: string | undefined): string {
    if (!valor) return '-';
    return valor
      .replaceAll('_', ' ')
      .toLowerCase()
      .replace(/\b\w/g, (letra) => letra.toUpperCase());
  }

  iniciais(nome: string): string {
    const partes = nome.trim().split(/\s+/).filter(Boolean);
    return (
      (partes[0]?.[0] ?? '') + (partes.length > 1 ? (partes.at(-1)?.[0] ?? '') : '')
    ).toUpperCase();
  }
}
