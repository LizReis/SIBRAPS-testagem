import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { Router, RouterModule } from '@angular/router';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';

@Component({
  selector: 'app-sidebar',
  imports: [RouterModule, CommonModule],
  templateUrl: './sidebar.html',
  styleUrl: './sidebar.css',
})
export class Sidebar implements OnInit {
  constructor(
    private router: Router,
    private usuarioLogadoService: UsuarioLogadoService,
  ) {}

  private readonly todosMenuItems = [
    { label: 'Início', route: '/inicio', icon: 'home' },
    { label: 'Painel', route: '/painel', icon: 'bar_chart' },
    { label: 'Agenda', route: '/agenda', icon: 'calendar_today' },
    { label: 'Pacientes', route: '/pacientes', icon: 'groups' },
    { label: 'Profissionais', route: '/profissionais', icon: 'person_add' },
    { label: 'Grupo Terapêutico', route: '/grupos-terapeuticos', icon: 'diversity_3' },
    { label: 'Relatórios', route: '/relatorios', icon: 'clinical_notes' },
  ];

  menuItems = this.todosMenuItems.filter((item) => item.route !== '/profissionais');

  ngOnInit(): void {
    this.usuarioLogadoService.obterUsuarioLogado().subscribe({
      next: (usuario) => {
        this.menuItems =
          usuario.tipoUsuario === 'PROFISSIONAL'
            ? this.todosMenuItems.filter((item) => item.route !== '/profissionais')
            : [...this.todosMenuItems];
      },
    });
  }

  logout() {
    const confirmado = confirm('Deseja realmente sair?');
    if (confirmado) {
      localStorage.removeItem('token');
      this.usuarioLogadoService.limparCache();
      this.router.navigate(['/login']);
    }
  }
}
