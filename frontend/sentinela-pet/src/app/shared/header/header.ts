import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { take } from 'rxjs';
import { InicioService } from '../../services/inicio-service';
import { UsuarioLogadoDTO, UsuarioLogadoService } from '../../services/usuario-logado-service';

@Component({
  selector: 'app-header',
  imports: [],
  templateUrl: './header.html',
  styleUrl: './header.css' })
export class Header implements OnInit {
  @Output() menuToggle = new EventEmitter<void>();
  private usuarioLogadoService = inject(UsuarioLogadoService);

  private inicioService = inject(InicioService);
  userInitials = '';
  userName = '';
  userRole = '';

  avatarColor = '#e8eef8';
  totalNotificacoes = 0;
  private colors = ['#e8eef8', '#dbeafe', '#e0f2fe', '#eef2ff'];

  ngOnInit(): void {
    this.usuarioLogadoService.obterUsuarioLogado().pipe(take(1)).subscribe({
      next: (usuario: UsuarioLogadoDTO) => {
        this.userName = usuario.nome;
        this.userRole = this.roleLabel(usuario.tipoUsuario);
        this.userInitials = this.getInitials(usuario.nome);
        this.avatarColor = this.getColor(usuario.nome);
      },
      error: () => { this.userInitials = this.getInitials('Usuário'); this.avatarColor = this.getColor('Usuário'); },
    });
    this.inicioService.buscarResumo().pipe(take(1)).subscribe({ next: r => this.totalNotificacoes = r.totalNotificacoes ?? 0, error: () => this.totalNotificacoes = 0 });
  }

  toggleMenu(): void { this.menuToggle.emit(); }
  private getInitials(name: string): string { return name.split(' ').filter(Boolean).slice(0, 2).map(n => n[0].toUpperCase()).join(''); }
  private getColor(name: string): string { return this.colors[name.split('').reduce((acc, c) => acc + c.charCodeAt(0), 0) % this.colors.length]; }
  private roleLabel(role: string): string { return ({ ADMINISTRADOR: 'Administrador', PROFISSIONAL: 'Profissional', RECEPCAO: 'Recepção' } as Record<string,string>)[role] ?? role; }
}
