import { CommonModule } from '@angular/common';
import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
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
    private cdr: ChangeDetectorRef,
  ) {}

  private readonly todosMenuItems = [
    { label: 'Início', route: '/inicio', icon: 'bi-house' },
    { label: 'Painel', route: '/painel', icon: 'bi-bar-chart' },
    { label: 'Agenda', route: '/agenda', icon: 'bi-calendar3' },
    { label: 'Pacientes', route: '/pacientes', icon: 'bi-people' },
    { label: 'Profissionais', route: '/profissionais', icon: 'bi-person-plus' },
    { label: 'Grupo Terapêutico', route: '/grupos-terapeuticos', icon: 'bi-people-fill' },
    { label: 'Relatórios', route: '/relatorios', icon: 'bi-file-earmark-medical' },
    { label: 'Pendências clínicas', route: '/pendencias-clinicas', icon: 'bi-clipboard-exclamation' },
  ];

  menuItems = this.menuParaPerfil();

  private menuParaPerfil(perfil?: string) {
    return this.todosMenuItems.filter(item => {
      if (item.route === '/pendencias-clinicas') return perfil === 'ADMINISTRADOR';
      if (item.route === '/profissionais') return perfil === 'ADMINISTRADOR' || perfil === 'RECEPCAO';
      return true;
    });
  }

  ngOnInit(): void {
    this.usuarioLogadoService.obterUsuarioLogado().subscribe({
      next: (usuario) => {
        this.menuItems = this.menuParaPerfil(usuario.tipoUsuario);
        this.cdr.markForCheck();
      },
      error: () => {
        this.menuItems = this.menuParaPerfil();
        this.cdr.markForCheck();
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
